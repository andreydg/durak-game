package com.example.durakgame.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Per-IP token-bucket rate limiting for the public API. Two buckets per client: a generous
 * general limit (covers polling + actions for several players behind one NAT) and a stricter
 * game-creation limit (POST /api/games and POST /api/games/quick-play) to blunt create-spam memory
 * growth. Buckets are evicted
 * once idle so the tracking map stays bounded. Generous by default so legitimate play is never
 * throttled; tune down via {@code app.ratelimit.*} for hostile environments.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final long IDLE_EVICTION_NANOS = 10L * 60 * 1_000_000_000L;
    private static final int MAX_TRACKED_CLIENTS = 100_000;
    private static final String OVERFLOW_KEY = "overflow";

    private final boolean enabled;
    private final int forwardedForHops;
    private final double generalBurst;
    private final double generalPerSecond;
    private final double createBurst;
    private final double createPerSecond;

    private final ConcurrentHashMap<String, TokenBucket> generalBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TokenBucket> createBuckets = new ConcurrentHashMap<>();

    /**
     * @param forwardedForHops which {@code X-Forwarded-For} entry, counted from the right, is the
     *     client: 1 when Cloud Run's front end is the only proxy; 2 behind an external load balancer
     *     that appends its own address too; 0 to ignore the header (direct connections).
     */
    @Autowired
    public RateLimitFilter(
            @Value("${app.ratelimit.enabled:true}") boolean enabled,
            @Value("${app.ratelimit.general-burst:120}") double generalBurst,
            @Value("${app.ratelimit.general-per-second:50}") double generalPerSecond,
            @Value("${app.ratelimit.create-burst:30}") double createBurst,
            @Value("${app.ratelimit.create-per-minute:60}") double createPerMinute,
            @Value("${app.ratelimit.forwarded-for-hops:1}") int forwardedForHops
    ) {
        this.enabled = enabled;
        this.forwardedForHops = forwardedForHops;
        this.generalBurst = generalBurst;
        this.generalPerSecond = generalPerSecond;
        this.createBurst = createBurst;
        this.createPerSecond = createPerMinute / 60.0;
    }

    RateLimitFilter(boolean enabled, double generalBurst, double generalPerSecond,
                    double createBurst, double createPerMinute) {
        this(enabled, generalBurst, generalPerSecond, createBurst, createPerMinute, 1);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        String path = routePath(request);
        return !(path.startsWith("/api/") || path.startsWith("/ws/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = clientKey(request);

        if (isGameCreation(request) && !allow(createBuckets, client, () -> new TokenBucket(createBurst, createPerSecond))) {
            reject(response, "Too many games created. Slow down.");
            return;
        }
        if (!allow(generalBuckets, client, () -> new TokenBucket(generalBurst, generalPerSecond))) {
            reject(response, "Too many requests. Slow down.");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isGameCreation(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = routePath(request);
        return "/api/games".equals(path) || "/api/games/quick-play".equals(path);
    }

    /** Uses the same decoded, matrix-parameter-free segment values that Spring matches. */
    private static String routePath(HttpServletRequest request) {
        try {
            RequestPath parsed = ServletRequestPathUtils.parse(request);
            StringBuilder resolved = new StringBuilder(parsed.pathWithinApplication().value().length());
            for (PathContainer.Element element : parsed.pathWithinApplication().elements()) {
                if (element instanceof PathContainer.PathSegment segment) {
                    resolved.append(segment.valueToMatch());
                } else {
                    resolved.append(element.value());
                }
            }
            return resolved.toString();
        } catch (IllegalArgumentException ignored) {
            /* Malformed encodings cannot match a controller route; retain API filtering when possible. */
            return request.getRequestURI();
        }
    }

    private boolean allow(ConcurrentHashMap<String, TokenBucket> buckets, String key, Supplier<TokenBucket> factory) {
        TokenBucket bucket = buckets.get(key);
        if (bucket == null) {
            // Bounded tracking: once full, unknown clients share one bucket until idle ones are evicted.
            String trackedKey = buckets.size() < MAX_TRACKED_CLIENTS ? key : OVERFLOW_KEY;
            bucket = buckets.computeIfAbsent(trackedKey, ignored -> factory.get());
        }
        return bucket.tryConsume();
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"" + message + "\"}");
    }

    /**
     * Identifies the client by the address the trusted proxy saw. Cloud Run's front end appends the
     * connecting address to {@code X-Forwarded-For}, so only entries counted from the right are
     * trustworthy; anything further left is client-supplied, and trusting it would let a caller mint
     * a fresh bucket per request. Spring's forwarded-header support has already replaced
     * {@code getRemoteAddr()} with that leftmost value and hidden the header, so read the raw request.
     */
    String clientKey(HttpServletRequest request) {
        HttpServletRequest raw = unwrap(request);
        String address = null;
        List<String> hops = forwardedFor(raw);
        if (!hops.isEmpty() && forwardedForHops > 0) {
            address = hops.get(Math.max(0, hops.size() - forwardedForHops));
        }
        if (address == null || address.isEmpty()) {
            address = raw.getRemoteAddr();
        }
        return bucketKey(address);
    }

    private static List<String> forwardedFor(HttpServletRequest raw) {
        List<String> hops = new ArrayList<>();
        Enumeration<String> headers = raw.getHeaders("X-Forwarded-For");
        while (headers != null && headers.hasMoreElements()) {
            for (String hop : headers.nextElement().split(",")) {
                if (!hop.isBlank()) {
                    hops.add(hop.trim());
                }
            }
        }
        return hops;
    }

    private static HttpServletRequest unwrap(HttpServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper wrapper) {
            current = wrapper.getRequest();
        }
        return current instanceof HttpServletRequest raw ? raw : request;
    }

    /** IPv6 clients are bucketed per /64, since a single subscriber typically controls the whole prefix. */
    static String bucketKey(String address) {
        try {
            InetAddress parsed = InetAddress.ofLiteral(address);   // literal parsing only, never DNS
            if (parsed instanceof Inet6Address) {
                return "v6:" + HexFormat.of().formatHex(parsed.getAddress(), 0, 8);
            }
            return parsed.getHostAddress();
        } catch (IllegalArgumentException notALiteral) {
            return address.length() > 64 ? address.substring(0, 64) : address;
        }
    }

    /** Evicts idle buckets so the per-IP tracking maps stay bounded. */
    @Scheduled(fixedDelayString = "${app.ratelimit.eviction-interval-ms:600000}")
    public void evictIdleBuckets() {
        long now = System.nanoTime();
        int removed = evictFrom(generalBuckets, now) + evictFrom(createBuckets, now);
        if (removed > 0) {
            log.debug("ratelimit_buckets_evicted count={}", removed);
        }
    }

    private int evictFrom(ConcurrentHashMap<String, TokenBucket> buckets, long now) {
        int[] removed = {0};
        buckets.entrySet().removeIf(entry -> {
            if (entry.getValue().idleNanos(now) > IDLE_EVICTION_NANOS) {
                removed[0]++;
                return true;
            }
            return false;
        });
        return removed[0];
    }
}
