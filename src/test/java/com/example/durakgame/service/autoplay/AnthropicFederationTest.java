package com.example.durakgame.service.autoplay;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The federation and the Messages transport against loopback stand-ins for Google and Anthropic. */
@Timeout(30)
class AnthropicFederationTest {
    static final String ASSERTION = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMDAwMDAwMDAwMDAwMDAwMDAwMDEifQ.c2lnbmF0dXJl";
    static final AnthropicFederation.Ids IDS = new AnthropicFederation.Ids(
            "6f1d0e2c-0000-4c6f-9a70-6d3e2f1a0b11", "wrkspc_durak", "svac_durak", "fdrl_durak");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong clock = new AtomicLong();
    private final ConcurrentLinkedDeque<Reply> tokenReplies = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Reply> messageReplies = new ConcurrentLinkedDeque<>();
    private final List<String> tokenBodies = new CopyOnWriteArrayList<>();
    private final List<Headers> messageHeaders = new CopyOnWriteArrayList<>();
    private final List<String> messageBodies = new CopyOnWriteArrayList<>();
    private volatile Reply identityReply = new Reply(200, ASSERTION + "\n");
    private volatile Headers identityHeaders;
    private volatile String identityQuery;
    private volatile CountDownLatch tokenGate = new CountDownLatch(0);
    private final CountDownLatch tokenEntered = new CountDownLatch(1);
    private final AtomicInteger identityCalls = new AtomicInteger();
    private HttpServer server;
    private ExecutorService serverThreads;
    private ListAppender<ILoggingEvent> logs;
    private Logger federationLogger;

    record Reply(int status, String body) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.createContext("/computeMetadata/v1/instance/service-accounts/default/identity", exchange -> {
            identityCalls.incrementAndGet();
            identityHeaders = exchange.getRequestHeaders();
            identityQuery = exchange.getRequestURI().getRawQuery();
            reply(exchange, identityReply, Map.of());
        });
        server.createContext("/v1/oauth/token", exchange -> {
            tokenBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            tokenEntered.countDown();
            try {
                tokenGate.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            Reply next = tokenReplies.poll();
            reply(exchange, next != null ? next : token("tok-" + tokenBodies.size(), 600), Map.of());
        });
        server.createContext("/v1/messages", exchange -> {
            messageHeaders.add(exchange.getRequestHeaders());
            messageBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Reply next = messageReplies.poll();
            if (next != null && next.status() == 0) {
                sleep(Duration.ofSeconds(2));
                next = new Reply(200, "{}");
            }
            reply(exchange, next != null ? next : new Reply(200, "{\"type\":\"message\"}"),
                    Map.of("request-id", "req_0123"));
        });
        server.start();
        federationLogger = (Logger) LoggerFactory.getLogger(AnthropicFederation.class);
        logs = new ListAppender<>();
        logs.start();
        federationLogger.addAppender(logs);
    }

    @AfterEach
    void stop() {
        federationLogger.detachAppender(logs);
        tokenGate.countDown();
        server.stop(0);
        serverThreads.shutdownNow();
    }

    /* ------------------------------------------------------------------ the exchange */

    @Test
    void exchangesTheServiceIdentityForABearerToken() throws Exception {
        AnthropicFederation federation = federation(IDS);

        assertEquals("tok-1", federation.accessToken(deadline()));

        assertEquals("Google", identityHeaders.getFirst("Metadata-Flavor"));
        assertEquals("audience=https://api.anthropic.com&format=full", identityQuery);
        assertEquals(1, tokenBodies.size());
        JsonNode body = objectMapper.readTree(tokenBodies.getFirst());
        assertEquals(Map.of(
                "grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer",
                "assertion", ASSERTION,
                "federation_rule_id", "fdrl_durak",
                "organization_id", "6f1d0e2c-0000-4c6f-9a70-6d3e2f1a0b11",
                "service_account_id", "svac_durak",
                "workspace_id", "wrkspc_durak"), objectMapper.convertValue(body, Map.class));
        assertEquals(List.of("autoplay_anthropic_federation outcome=READY trigger=CALL latencyMs=0"), lines());
    }

    @Test
    void aSingleWorkspaceRuleMayLeaveTheWorkspaceOut() throws Exception {
        federation(new AnthropicFederation.Ids(IDS.organizationId(), " ", IDS.serviceAccountId(),
                IDS.federationRuleId())).accessToken(deadline());

        assertTrue(objectMapper.readTree(tokenBodies.getFirst()).path("workspace_id").isMissingNode());
    }

    @Test
    void reusesTheTokenUntilShortlyBeforeItExpires() throws Exception {
        AnthropicFederation federation = federation(IDS);

        assertEquals("tok-1", federation.accessToken(deadline()));
        clock.addAndGet(Duration.ofSeconds(479).toNanos());
        assertEquals("tok-1", federation.accessToken(deadline()));
        assertEquals(1, tokenBodies.size());

        // A 600 s token is replaced 120 s before it expires.
        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        assertEquals("tok-2", federation.accessToken(deadline()));
        assertEquals(2, tokenBodies.size());
    }

    @Test
    void aShortTokenIsReplacedHalfwayThroughItsLife() throws Exception {
        tokenReplies.add(token("tok-short", 60));
        AnthropicFederation federation = federation(IDS);

        assertEquals("tok-short", federation.accessToken(deadline()));
        clock.addAndGet(Duration.ofSeconds(30).toNanos());
        assertEquals("tok-2", federation.accessToken(deadline()));
    }

    @Test
    void forgettingTheTokenInUseStartsANewExchange() throws Exception {
        AnthropicFederation federation = federation(IDS);
        String first = federation.accessToken(deadline());

        federation.forget("some-older-token");
        assertEquals(first, federation.accessToken(deadline()));
        federation.forget(first);
        assertEquals("tok-2", federation.accessToken(deadline()));
    }

    @Test
    void anthropicsRefusalsAreClosedFailuresThatNeverCarryTheBody() throws Exception {
        Map<Reply, AnthropicFederation.Failure> cases = Map.ofEntries(
                Map.entry(new Reply(401, "secret-body"), AnthropicFederation.Failure.DENIED),
                Map.entry(new Reply(403, "secret-body"), AnthropicFederation.Failure.DENIED),
                Map.entry(new Reply(429, "secret-body"), AnthropicFederation.Failure.RATE_LIMITED),
                Map.entry(new Reply(503, "secret-body"), AnthropicFederation.Failure.PROVIDER),
                Map.entry(new Reply(400, "secret-body"), AnthropicFederation.Failure.REJECTED),
                Map.entry(new Reply(200, "secret-body"), AnthropicFederation.Failure.MALFORMED),
                Map.entry(new Reply(200, "{\"access_token\":\"tok\",\"token_type\":\"mac\",\"expires_in\":600}"),
                        AnthropicFederation.Failure.MALFORMED),
                Map.entry(new Reply(200, "{\"access_token\":\"tok\",\"token_type\":\"bearer\","
                        + "\"expires_in\":18446744073709551617}"), AnthropicFederation.Failure.MALFORMED),
                Map.entry(new Reply(200, "{\"access_token\":\"tok\\r\\nX-Injected: 1\",\"token_type\":\"bearer\","
                        + "\"expires_in\":600}"), AnthropicFederation.Failure.MALFORMED),
                Map.entry(new Reply(200, "{\"access_token\":\"a\",\"access_token\":\"b\",\"token_type\":\"bearer\","
                        + "\"expires_in\":600}"), AnthropicFederation.Failure.MALFORMED));

        for (Map.Entry<Reply, AnthropicFederation.Failure> entry : cases.entrySet()) {
            tokenReplies.add(entry.getKey());
            AnthropicFederation.Unavailable failure = assertThrows(AnthropicFederation.Unavailable.class,
                    () -> federation(IDS).accessToken(deadline()), entry.getKey().toString());
            assertEquals(entry.getValue(), failure.failure(), entry.getKey().toString());
            assertEquals("autoplay_anthropic_federation outcome=UNAVAILABLE failure=" + entry.getValue()
                    + " trigger=CALL latencyMs=0", lines().getLast());
        }
        for (String line : lines()) {
            assertFalse(line.contains("secret-body") || line.contains(ASSERTION), line);
        }
    }

    @Test
    void withoutAnIdentityTokenAnthropicIsNeverAsked() {
        identityReply = new Reply(404, "no such account");
        assertEquals(AnthropicFederation.Failure.IDENTITY, assertThrows(AnthropicFederation.Unavailable.class,
                () -> federation(IDS).accessToken(deadline())).failure());

        identityReply = new Reply(200, "not-a-jwt");
        assertEquals(AnthropicFederation.Failure.IDENTITY, assertThrows(AnthropicFederation.Unavailable.class,
                () -> federation(IDS).accessToken(deadline())).failure());

        assertEquals(0, tokenBodies.size());
    }

    @Test
    void withoutTheIdsNothingTouchesTheNetwork() {
        for (AnthropicFederation.Ids ids : List.of(AnthropicFederation.Ids.NONE,
                new AnthropicFederation.Ids("", "wrkspc_durak", "svac_durak", "fdrl_durak"),
                new AnthropicFederation.Ids("org", "wrkspc_durak", " ", "fdrl_durak"),
                new AnthropicFederation.Ids("org", "wrkspc_durak", "svac_durak", null))) {
            AnthropicFederation federation = federation(ids);
            assertFalse(federation.configured());

            federation.exchangeAtStartup();
            assertEquals(AnthropicFederation.Failure.UNCONFIGURED, assertThrows(AnthropicFederation.Unavailable.class,
                    () -> federation.accessToken(deadline())).failure());
        }

        assertEquals(0, identityCalls.get());
        assertEquals(0, tokenBodies.size());
        assertTrue(lines().isEmpty());
    }

    @Test
    void startupExchangesOnceAndCallsReuseItsToken() throws Exception {
        AnthropicFederation federation = federation(IDS);

        federation.exchangeAtStartup();

        assertEquals(List.of("autoplay_anthropic_federation outcome=READY trigger=BOOT latencyMs=0"), lines());
        assertEquals("tok-1", federation.accessToken(deadline()));
        assertEquals(1, tokenBodies.size());
    }

    @Test
    void aFailedStartupExchangeNeverStopsStartup() {
        tokenReplies.add(new Reply(401, "{}"));

        federation(IDS).exchangeAtStartup();

        assertEquals(List.of("autoplay_anthropic_federation outcome=UNAVAILABLE failure=DENIED trigger=BOOT "
                + "latencyMs=0"), lines());
    }

    @Test
    void concurrentCallsShareOneExchange() throws Exception {
        AnthropicFederation federation = federation(IDS);
        tokenGate = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = callers.submit(() -> federation.accessToken(deadline()));
            assertTrue(tokenEntered.await(10, TimeUnit.SECONDS));
            Future<String> second = callers.submit(() -> federation.accessToken(deadline()));
            tokenGate.countDown();

            assertEquals("tok-1", first.get(10, TimeUnit.SECONDS));
            assertEquals("tok-1", second.get(10, TimeUnit.SECONDS));
            assertEquals(1, tokenBodies.size());
        } finally {
            callers.shutdownNow();
        }
    }

    /* ------------------------------------------------------------------ the Messages transport */

    @Test
    void messagesCarryTheBearerTokenAndTheApiVersion() throws Exception {
        AnthropicMessagesTransport transport = transport(federation(IDS), clock::get);

        AnthropicMessagesTransport.HttpResult result = transport.post("{\"model\":\"claude-opus-5-5\"}",
                Duration.ofSeconds(5));

        assertEquals(200, result.statusCode());
        assertEquals("{\"type\":\"message\"}", result.body());
        assertEquals("req_0123", result.requestId());
        Headers headers = messageHeaders.getFirst();
        assertEquals("Bearer tok-1", headers.getFirst("Authorization"));
        assertEquals("2023-06-01", headers.getFirst("anthropic-version"));
        assertEquals("application/json", headers.getFirst("Content-Type"));
        assertNull(headers.getFirst("x-api-key"), "there is no API key");
        assertNull(headers.getFirst("anthropic-beta"), "every feature used is generally available");
        assertEquals("{\"model\":\"claude-opus-5-5\"}", messageBodies.getFirst());
    }

    @Test
    void aRefusedTokenIsForgottenSoTheNextCallExchangesAgain() throws Exception {
        AnthropicMessagesTransport transport = transport(federation(IDS), clock::get);
        messageReplies.add(new Reply(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\"}}"));

        assertEquals(401, transport.post("{}", Duration.ofSeconds(5)).statusCode());
        assertEquals(200, transport.post("{}", Duration.ofSeconds(5)).statusCode());

        assertEquals("Bearer tok-1", messageHeaders.get(0).getFirst("Authorization"));
        assertEquals("Bearer tok-2", messageHeaders.get(1).getFirst("Authorization"));
    }

    @Test
    void aFailedExchangeSendsNothing() {
        tokenReplies.add(new Reply(403, "{}"));
        AnthropicMessagesTransport transport = transport(federation(IDS), clock::get);

        assertEquals(AnthropicFederation.Failure.DENIED, assertThrows(AnthropicFederation.Unavailable.class,
                () -> transport.post("{}", Duration.ofSeconds(5))).failure());
        assertTrue(messageBodies.isEmpty());
    }

    @Test
    void theTimeoutCoversTheWholeResponse() {
        AnthropicFederation federation = new AnthropicFederation(IDS, identityUri(), tokenUri(),
                HttpClient.newHttpClient(), System::nanoTime, Duration.ofSeconds(5), Duration.ofSeconds(2));
        AnthropicMessagesTransport transport = transport(federation, System::nanoTime);
        messageReplies.add(new Reply(0, ""));
        long started = System.nanoTime();

        assertThrows(HttpTimeoutException.class, () -> transport.post("{}", Duration.ofMillis(500)));

        assertTrue(System.nanoTime() - started < Duration.ofMillis(1900).toNanos(), "the slow reply was not awaited");
    }

    /* ------------------------------------------------------------------ helpers */

    private AnthropicFederation federation(AnthropicFederation.Ids ids) {
        return new AnthropicFederation(ids, identityUri(), tokenUri(), HttpClient.newHttpClient(), clock::get,
                Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    private AnthropicMessagesTransport transport(AnthropicFederation federation, LongSupplier nanoTime) {
        return AnthropicMessagesTransport.federated(federation, base().resolve("/v1/messages"),
                HttpClient.newHttpClient(), nanoTime);
    }

    private OutboundDeadline deadline() {
        return OutboundDeadline.after(Duration.ofSeconds(10), clock::get);
    }

    private URI identityUri() {
        return base().resolve("/computeMetadata/v1/instance/service-accounts/default/identity"
                + "?audience=https://api.anthropic.com&format=full");
    }

    private URI tokenUri() {
        return base().resolve("/v1/oauth/token");
    }

    private URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private Reply token(String value, long expiresIn) {
        return new Reply(200, "{\"access_token\":\"" + value + "\",\"token_type\":\"Bearer\",\"expires_in\":"
                + expiresIn + ",\"scope\":\"workspace:developer\"}");
    }

    private List<String> lines() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static void reply(HttpExchange exchange, Reply reply, Map<String, String> headers) throws IOException {
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
