package com.example.durakgame.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final boolean enabled;
    private final double generalBurst;
    private final double generalPerSecond;
    private final double createBurst;
    private final double createPerSecond;

    private final ConcurrentHashMap<String, TokenBucket> generalBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TokenBucket> createBuckets = new ConcurrentHashMap<>();

    public RateLimitFilter(
            @Value("${app.ratelimit.enabled:true}") boolean enabled,
            @Value("${app.ratelimit.general-burst:120}") double generalBurst,
            @Value("${app.ratelimit.general-per-second:50}") double generalPerSecond,
            @Value("${app.ratelimit.create-burst:30}") double createBurst,
            @Value("${app.ratelimit.create-per-minute:60}") double createPerMinute
    ) {
        this.enabled = enabled;
        this.generalBurst = generalBurst;
        this.generalPerSecond = generalPerSecond;
        this.createBurst = createBurst;
        this.createPerSecond = createPerMinute / 60.0;
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
        String ip = clientIp(request);

        if (isGameCreation(request) && !allow(createBuckets, ip, () -> new TokenBucket(createBurst, createPerSecond))) {
            reject(response, "Too many games created — slow down.");
            return;
        }
        if (!allow(generalBuckets, ip, () -> new TokenBucket(generalBurst, generalPerSecond))) {
            reject(response, "Too many requests — slow down.");
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

    private boolean allow(ConcurrentHashMap<String, TokenBucket> buckets, String ip, Supplier<TokenBucket> factory) {
        return buckets.computeIfAbsent(ip, ignored -> factory.get()).tryConsume();
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"" + message + "\"}");
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
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
