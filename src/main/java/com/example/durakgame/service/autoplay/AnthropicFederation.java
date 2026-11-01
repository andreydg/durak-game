package com.example.durakgame.service.autoplay;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Workload identity federation with Anthropic, as Glass Bead's direct route does it. The service's
 * Cloud Run identity takes a Google-signed identity token for Anthropic's audience from the metadata
 * server and exchanges it at Anthropic's token endpoint for a short-lived access token, which the
 * Messages calls send as the bearer. There is no API key: the four ids name the federation rule set
 * up in the Claude Console, and they are not credentials.
 *
 * <p>The access token is cached and replaced before it expires: it counts as fresh until the
 * smaller of 120 s and half its lifetime before {@code expires_in} runs out. One exchange runs at a
 * time, on a thread of its own; a call that needs a token while one is under way waits for that one,
 * only as long as its own time allows, and leaves it running for the others. Every exchange presents
 * a fresh identity token. A failed exchange fails the calls that waited for it and is never retried:
 * the next call that needs a token starts the next exchange.
 *
 * <p>When the ids are set, startup exchanges once and waits for it at most {@link #BOOT_WAIT}, even
 * while another provider plays the bots, so a new revision's log shows whether federation works
 * before the switch. Without the ids nothing here touches the network.
 *
 * <p>Each exchange logs one {@code autoplay_anthropic_federation} line: ready, or unavailable with a
 * closed {@link Failure}, how long it took and what asked for it. No line or exception message
 * carries a token, the exchange's response or an error body.
 */
@Component
public class AnthropicFederation {
    private static final Logger log = LoggerFactory.getLogger(AnthropicFederation.class);

    /** By address, so there is no name to resolve first. */
    static final URI IDENTITY = URI.create("http://169.254.169.254/computeMetadata/v1/instance/"
            + "service-accounts/default/identity?audience=https://api.anthropic.com&format=full");
    static final URI TOKEN = URI.create("https://api.anthropic.com/v1/oauth/token");
    static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    /** Both requests of one exchange; one takes a few hundred milliseconds. */
    static final Duration EXCHANGE_TIMEOUT = Duration.ofSeconds(10);
    /** How long startup waits for its exchange; the exchange itself may finish later. */
    static final Duration BOOT_WAIT = Duration.ofSeconds(2);
    /** Anthropic's own clients start refreshing two minutes before expiry. */
    static final Duration REFRESH_AHEAD = Duration.ofSeconds(120);
    /** Anthropic accepts an assertion of at most 16 KiB, and a token response is far smaller. */
    static final int MAX_BODY = 16 * 1024;
    private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    /** RFC 6750's b64token, so a token can only ever be a header value and never break one. */
    private static final Pattern BEARER = Pattern.compile("[A-Za-z0-9._~+/-]{1,4096}=*");
    /** One unambiguous token response: no repeated field, nothing after the object. */
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** Why an exchange gave no token, in the order an exchange can fail. */
    public enum Failure {
        /** The ids are not set, so nothing was sent. */
        UNCONFIGURED,
        /** The metadata server gave no identity token: unreachable, slow, refused, or not a token. */
        IDENTITY,
        /** Anthropic's token endpoint did not answer in time. */
        TIMEOUT,
        /** The exchange could not be sent or its answer not received. */
        NETWORK,
        /** Anthropic refused the identity (401 or 403): the rule, the ids or the account do not match. */
        DENIED,
        /** Anthropic rejected the request itself (another 4xx), such as a malformed id. */
        REJECTED,
        /** Anthropic asked the exchange to slow down (429). */
        RATE_LIMITED,
        /** Anthropic failed (5xx). */
        PROVIDER,
        /** Anthropic answered 200 with something that is not one usable bearer token. */
        MALFORMED
    }

    /** Who asked for an exchange: startup, or a call that needed a token. */
    enum Trigger { BOOT, CALL }

    /** The federation set up in the Claude Console; a single-workspace rule may leave the workspace out. */
    record Ids(String organizationId, String workspaceId, String serviceAccountId, String federationRuleId) {
        static final Ids NONE = new Ids("", "", "", "");

        Ids {
            organizationId = clean(organizationId);
            workspaceId = clean(workspaceId);
            serviceAccountId = clean(serviceAccountId);
            federationRuleId = clean(federationRuleId);
        }

        /** The ids an exchange needs are set. */
        boolean configured() {
            return !organizationId.isEmpty() && !serviceAccountId.isEmpty() && !federationRuleId.isEmpty();
        }

        private static String clean(String value) {
            return value == null ? "" : value.strip();
        }
    }

    private final Ids ids;
    private final URI identity;
    private final URI token;
    private final HttpClient http;
    private final LongSupplier nanoTime;
    private final Duration exchangeTimeout;
    private final Duration bootWait;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<Token> cached = new AtomicReference<>();
    /** The one exchange thread, started by the first exchange. */
    private final Executor exchanges = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "anthropic-federation");
        thread.setDaemon(true);
        return thread;
    });
    private CompletableFuture<Token> pending;

    @Autowired
    public AnthropicFederation(
            @Value("${autoplay.anthropic.organization-id:}") String organizationId,
            @Value("${autoplay.anthropic.workspace-id:}") String workspaceId,
            @Value("${autoplay.anthropic.service-account-id:}") String serviceAccountId,
            @Value("${autoplay.anthropic.federation-rule-id:}") String federationRuleId
    ) {
        this(new Ids(organizationId, workspaceId, serviceAccountId, federationRuleId), IDENTITY, TOKEN,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), System::nanoTime,
                EXCHANGE_TIMEOUT, BOOT_WAIT);
    }

    /** Package-private for tests: loopback endpoints, a test clock and shorter times. */
    AnthropicFederation(Ids ids, URI identity, URI token, HttpClient http, LongSupplier nanoTime,
                        Duration exchangeTimeout, Duration bootWait) {
        this.ids = ids;
        this.identity = identity;
        this.token = token;
        this.http = http;
        this.nanoTime = nanoTime;
        this.exchangeTimeout = exchangeTimeout;
        this.bootWait = bootWait;
    }

    /** Whether the ids are set; without them no call is ever made. */
    boolean configured() {
        return ids.configured();
    }

    /**
     * The startup exchange, only with the ids set. Startup waits for it at most {@link #BOOT_WAIT};
     * the exchange logs its own outcome, however late, and its failure never reaches startup.
     */
    @PostConstruct
    void exchangeAtStartup() {
        if (!configured()) {
            return;
        }
        try {
            OutboundDeadline.after(bootWait, nanoTime).await(exchange(Trigger.BOOT), OutboundDeadline.Abandon.LEAVE_RUNNING);
        } catch (IOException unavailableOrLate) {
            // The exchange's own line says which.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A fresh access token, exchanging for one when none is, within the call's own time.
     *
     * @throws Unavailable when the exchange fails or the ids are not set
     * @throws IOException  when the call's time runs out before the exchange ends
     */
    String accessToken(OutboundDeadline deadline) throws IOException, InterruptedException {
        if (!configured()) {
            throw new Unavailable(Failure.UNCONFIGURED);
        }
        Token current = cached.get();
        if (current != null && current.freshAt(nanoTime.getAsLong())) {
            return current.value();
        }
        return deadline.await(exchange(Trigger.CALL), OutboundDeadline.Abandon.LEAVE_RUNNING).value();
    }

    /** Messages refused {@code used} (401): drop it, unless a newer token has replaced it already. */
    void forget(String used) {
        Token current = cached.get();
        if (current != null && current.value().equals(used)) {
            cached.compareAndSet(current, null);
        }
    }

    /** The exchange under way, or a new one; a token another call just received ends the wait. */
    private synchronized CompletableFuture<Token> exchange(Trigger trigger) {
        if (pending != null && !pending.isDone()) {
            return pending;
        }
        Token current = cached.get();
        if (current != null && current.freshAt(nanoTime.getAsLong())) {
            return CompletableFuture.completedFuture(current);
        }
        CompletableFuture<Token> next = new CompletableFuture<>();
        pending = next;
        exchanges.execute(() -> run(trigger, next));
        return next;
    }

    private void run(Trigger trigger, CompletableFuture<Token> result) {
        long started = nanoTime.getAsLong();
        try {
            Token fresh = exchangeOnce(started);
            cached.set(fresh);
            logExchange(trigger, null, started);
            result.complete(fresh);
        } catch (Unavailable unavailable) {
            logExchange(trigger, unavailable.failure(), started);
            result.completeExceptionally(unavailable);
        } catch (RuntimeException unexpected) {
            // Never the exception, which could describe the request; the waiting calls still end.
            logExchange(trigger, Failure.NETWORK, started);
            result.completeExceptionally(new Unavailable(Failure.NETWORK));
        }
    }

    private Token exchangeOnce(long started) throws Unavailable {
        OutboundDeadline deadline = OutboundDeadline.after(exchangeTimeout, nanoTime);
        String assertion = identityToken(deadline);
        ObjectNode body = mapper.createObjectNode();
        body.put("grant_type", GRANT_TYPE);
        body.put("assertion", assertion);
        body.put("federation_rule_id", ids.federationRuleId());
        body.put("organization_id", ids.organizationId());
        body.put("service_account_id", ids.serviceAccountId());
        if (!ids.workspaceId().isEmpty()) {
            body.put("workspace_id", ids.workspaceId());
        }
        HttpResponse<byte[]> response;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(token)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            // An error body is never read, so it can never be kept or logged.
            response = deadline.send(http, request, OutboundDeadline.firstBytes(MAX_BODY, ok()));
        } catch (HttpTimeoutException late) {
            throw new Unavailable(Failure.TIMEOUT);
        } catch (IOException failed) {
            throw new Unavailable(Failure.NETWORK);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Unavailable(Failure.NETWORK);
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new Unavailable(Failure.DENIED);
        }
        if (status == 429) {
            throw new Unavailable(Failure.RATE_LIMITED);
        }
        if (status >= 500) {
            throw new Unavailable(Failure.PROVIDER);
        }
        if (status != 200) {
            throw new Unavailable(Failure.REJECTED);
        }
        return read(response.body(), started);
    }

    /** A fresh identity token for Anthropic's audience, with the account's email ({@code format=full}). */
    private String identityToken(OutboundDeadline deadline) throws Unavailable {
        HttpResponse<byte[]> response;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(identity)
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Metadata-Flavor", "Google")
                    .GET();
            response = deadline.send(http, request, OutboundDeadline.firstBytes(MAX_BODY, ok()));
        } catch (IOException failed) {
            throw new Unavailable(Failure.IDENTITY);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Unavailable(Failure.IDENTITY);
        }
        if (response.statusCode() != 200 || response.body().length > MAX_BODY) {
            throw new Unavailable(Failure.IDENTITY);
        }
        String assertion = new String(response.body(), StandardCharsets.US_ASCII).strip();
        if (!JWT.matcher(assertion).matches()) {
            throw new Unavailable(Failure.IDENTITY);
        }
        return assertion;
    }

    /** One bearer token with a lifetime, or nothing usable. */
    private Token read(byte[] bytes, long started) throws Unavailable {
        if (bytes.length > MAX_BODY) {
            throw new Unavailable(Failure.MALFORMED);
        }
        JsonNode root;
        try {
            root = STRICT.readTree(bytes);
        } catch (IOException malformed) {
            throw new Unavailable(Failure.MALFORMED);
        }
        if (root == null || !root.isObject()) {
            throw new Unavailable(Failure.MALFORMED);
        }
        JsonNode value = root.path("access_token");
        JsonNode type = root.path("token_type");
        JsonNode lifetime = root.path("expires_in");
        // A BigInteger is integral too, and asLong() would truncate it, so the lifetime must fit in a
        // long before its range is read.
        if (!value.isTextual() || !BEARER.matcher(value.asText()).matches()
                || !type.isTextual() || !"bearer".equalsIgnoreCase(type.asText())
                || !lifetime.isIntegralNumber() || !lifetime.canConvertToLong()
                || lifetime.asLong() < 1 || lifetime.asLong() > 86_400) {
            throw new Unavailable(Failure.MALFORMED);
        }
        // Counted from before the identity token was fetched, so the cache errs early.
        long lifetimeNanos = Duration.ofSeconds(lifetime.asLong()).toNanos();
        long ahead = Math.min(REFRESH_AHEAD.toNanos(), lifetimeNanos / 2);
        return new Token(value.asText(), started + lifetimeNanos - ahead);
    }

    private void logExchange(Trigger trigger, Failure failure, long started) {
        long latencyMs = Math.max(0, (nanoTime.getAsLong() - started) / 1_000_000);
        if (failure == null) {
            log.info("autoplay_anthropic_federation outcome=READY trigger={} latencyMs={}", trigger, latencyMs);
        } else {
            log.warn("autoplay_anthropic_federation outcome=UNAVAILABLE failure={} trigger={} latencyMs={}",
                    failure, trigger, latencyMs);
        }
    }

    private static Predicate<HttpResponse.ResponseInfo> ok() {
        return info -> info.statusCode() == 200;
    }

    /** An access token and the moment it stops counting as fresh, on the monotonic clock. */
    private record Token(String value, long freshUntil) {
        boolean freshAt(long now) {
            return now - freshUntil < 0;
        }

        @Override
        public String toString() {
            return "Token[redacted]";
        }
    }

    /** An exchange that gave no token. Its message is fixed and its failure closed. */
    static final class Unavailable extends IOException {
        private final Failure failure;

        Unavailable(Failure failure) {
            super("Anthropic federation unavailable");
            this.failure = failure;
        }

        Failure failure() {
            return failure;
        }
    }
}
