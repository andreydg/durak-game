package com.example.durakgame.websocket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.socket.CloseStatus;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real handshakes against the running server, written by hand so the test controls every header
 * a browser or Cloud Run's front end would send, and can behave like a client that never answers.
 * Each test uses its own X-Forwarded-For addresses (the proxy-appended entry is the client key)
 * and rooms, so their sockets don't share a cap.
 */
@Timeout(60)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.websocket.max-sessions-per-client=2",
        "app.websocket.max-sessions-per-game=3",
        "autoplay.gemini.enabled=false"
})
class WebSocketHandshakeIntegrationTest {
    private static final int OPCODE_TEXT = 0x1;
    private static final int OPCODE_BINARY = 0x2;
    private static final int OPCODE_CLOSE = 0x8;
    private static final int END_OF_STREAM = -1;
    private static final int STILL_OPEN = -2;

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private GameWebSocketHandler gameWebSocketHandler;

    @Test
    void theSiteItselfMayOpenSockets() throws IOException {
        try (Handshake handshake = handshake("/ws/lobbies", "localhost:" + port, origin(), "198.51.100.1")) {
            assertEquals(101, handshake.status());
            assertEquals(OPCODE_TEXT, handshake.firstFrameOpcode(), "LOBBIES_READY");
        }
    }

    @Test
    void otherSitesMayNot() throws IOException {
        try (Handshake handshake = handshake("/ws/lobbies", "localhost:" + port, "https://evil.example", "198.51.100.2")) {
            assertEquals(403, handshake.status());
        }
    }

    @Test
    void behindCloudRunTheSitesHttpsOriginIsItsOwn() throws IOException {
        // Cloud Run terminates TLS and forwards plain HTTP with X-Forwarded-Proto; the forwarded-header
        // filter restores https://durak.example, so the browser's Origin counts as same-origin.
        try (Handshake handshake = handshake("/ws/lobbies", "durak.example", "https://durak.example", "198.51.100.3",
                "X-Forwarded-Proto: https")) {
            assertEquals(101, handshake.status());
        }
        try (Handshake handshake = handshake("/ws/lobbies", "durak.example", "https://evil.example", "198.51.100.3",
                "X-Forwarded-Proto: https")) {
            assertEquals(403, handshake.status());
        }
    }

    @Test
    void gameSocketsNeedSomethingThatCanBeARoomCode() throws IOException {
        try (Handshake invented = handshake("/ws/games/not-a-room", "localhost:" + port, origin(), "198.51.100.4")) {
            assertEquals(404, invented.status());
        }
        try (Handshake room = handshake("/ws/games/ABCD23", "localhost:" + port, origin(), "198.51.100.4")) {
            assertEquals(101, room.status());
        }
    }

    @Test
    void oneAddressHoldsOnlyALimitedNumberOfSockets() throws Exception {
        List<Handshake> open = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                Handshake admitted = handshake("/ws/lobbies", "localhost:" + port, origin(), "203.0.113.50");
                open.add(admitted);
                assertEquals(OPCODE_TEXT, admitted.firstFrameOpcode());
            }
            try (Handshake surplus = handshake("/ws/lobbies", "localhost:" + port, origin(), "203.0.113.50")) {
                assertEquals(429, surplus.status(), "refused before the upgrade, not opened and closed");
                assertTrue(surplus.closedByServerWithin(Duration.ofSeconds(2)));
            }
            try (Handshake otherAddress = handshake("/ws/lobbies", "localhost:" + port, origin(), "203.0.113.51")) {
                assertEquals(OPCODE_TEXT, otherAddress.firstFrameOpcode());
            }

            open.removeFirst().close();
            assertTrue(admittedWithin("203.0.113.50", 3_000), "a closed socket frees its place");
        } finally {
            for (Handshake handshake : open) {
                handshake.close();
            }
        }
    }

    /**
     * The review's probe: hold the address's two sockets, then keep opening more without ever
     * reading or answering anything. Each extra one used to upgrade (101) and wait for the client
     * to acknowledge a close frame, uncounted, for up to 30 seconds.
     */
    @Test
    void aClientThatNeverAcknowledgesAnythingCannotHoldExtraSockets() throws Exception {
        String address = "203.0.113.60";
        List<Handshake> held = new ArrayList<>();
        List<Handshake> surplus = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                held.add(handshake("/ws/lobbies", "localhost:" + port, origin(), address));
                assertEquals(101, held.getLast().status());
            }

            // Kept open on the client's side and never read past the response head.
            for (int i = 0; i < 10; i++) {
                surplus.add(handshake("/ws/lobbies", "localhost:" + port, origin(), address));
                assertEquals(429, surplus.getLast().status());
            }
            for (Handshake refused : surplus) {
                assertTrue(refused.closedByServerWithin(Duration.ofSeconds(2)),
                        "the server closes a refused connection without waiting for the client");
            }

            // The refusals took no places: freeing one admits exactly one more.
            held.removeFirst().close();
            Handshake replacement = openWithin(address, 3_000);
            assertTrue(replacement != null, "the freed place was not given back");
            held.add(replacement);
            try (Handshake third = handshake("/ws/lobbies", "localhost:" + port, origin(), address)) {
                assertEquals(429, third.status());
            }
        } finally {
            for (Handshake handshake : held) {
                handshake.close();
            }
            for (Handshake handshake : surplus) {
                handshake.close();
            }
        }
    }

    @Test
    void handshakesRefusedAfterTheReservationGiveItBack() throws Exception {
        String address = "203.0.113.70";
        for (int i = 0; i < 3; i++) {
            try (Handshake foreign = handshake("/ws/lobbies", "localhost:" + port, "https://evil.example", address)) {
                assertEquals(403, foreign.status(), "origin check");
            }
            try (Handshake badVersion = handshake("/ws/lobbies", "localhost:" + port, origin(), address,
                    "Sec-WebSocket-Version: 7")) {
                assertEquals(426, badVersion.status(), "unsupported websocket version");
            }
        }

        List<Handshake> open = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                open.add(handshake("/ws/lobbies", "localhost:" + port, origin(), address));
                assertEquals(OPCODE_TEXT, open.getLast().firstFrameOpcode(), "the refusals leaked no place");
            }
            try (Handshake third = handshake("/ws/lobbies", "localhost:" + port, origin(), address)) {
                assertEquals(429, third.status());
            }
        } finally {
            for (Handshake handshake : open) {
                handshake.close();
            }
        }
    }

    @Test
    void aRoomHoldsOnlyALimitedNumberOfSockets() throws Exception {
        List<Handshake> open = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                open.add(handshake("/ws/games/WXYZ23", "localhost:" + port, origin(), "203.0.113.8" + i));
                assertEquals(101, open.getLast().status());
            }
            try (Handshake fourth = handshake("/ws/games/wxyz23", "localhost:" + port, origin(), "203.0.113.89")) {
                assertEquals(429, fourth.status());
            }
            try (Handshake otherRoom = handshake("/ws/games/WXYZ24", "localhost:" + port, origin(), "203.0.113.89")) {
                assertEquals(101, otherRoom.status());
            }
        } finally {
            for (Handshake handshake : open) {
                handshake.close();
            }
        }
    }

    /**
     * A client that stops reading fills its socket, then its queue. Broadcasting must not wait for
     * it, and once the sender gives up, the server closes the connection right after its close
     * frame although this client never answers it.
     */
    @Test
    void aClientThatStopsReadingIsDroppedWithoutHoldingUpBroadcasts() throws Exception {
        String room = "STUK23";
        String address = "203.0.113.90";
        String bigMessage = "x".repeat(64 * 1024);
        try (Handshake stuck = handshake("/ws/games/" + room, "localhost:" + port, origin(), address, 4_096);
             Handshake second = handshake("/ws/lobbies", "localhost:" + port, origin(), address)) {
            assertEquals(101, stuck.status());
            assertEquals(101, second.status());
            try (Handshake third = handshake("/ws/lobbies", "localhost:" + port, origin(), address)) {
                assertEquals(429, third.status(), "the stuck socket holds its place while it is open");
            }

            long started = System.nanoTime();
            for (int i = 0; i < 400; i++) {
                gameWebSocketHandler.broadcastBotThinking(room, "bot", true, bigMessage);
            }
            long broadcastingMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(broadcastingMs < 2_000, "400 broadcasts took " + broadcastingMs + " ms");

            assertEquals(CloseStatus.SESSION_NOT_RELIABLE.getCode(), stuck.readUntilCloseFrame(),
                    "the sender closed it as too far behind");
            assertEquals(END_OF_STREAM, stuck.nextByteWithin(Duration.ofSeconds(3)),
                    "the connection is dropped after the close frame, not held for the client's reply");
            assertTrue(admittedWithin(address, 3_000), "its place is free again");
        }
    }

    /**
     * The second review's probe, on both endpoints: one binary message, and the server's 1003 close
     * frame never answered. The inherited handling closed the ordinary way, so the connection
     * stayed open waiting for the reply after afterConnectionClosed had already given back its
     * place, and one address could keep any number of them open.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/ws/lobbies", "/ws/games/BNRY23"})
    void aBinaryMessageDropsTheConnectionAndGivesBackItsPlace(String path) throws Exception {
        String address = path.startsWith("/ws/games/") ? "203.0.113.110" : "203.0.113.111";
        List<Handshake> open = new ArrayList<>();
        try {
            Handshake idle = handshake(path, "localhost:" + port, origin(), address);
            open.add(idle);
            assertEquals(101, idle.status());
            for (int i = 0; i < 6; i++) {
                Handshake binary = handshake(path, "localhost:" + port, origin(), address);
                open.add(binary);
                assertEquals(101, binary.status(), "attempt " + i);
                try (Handshake third = handshake(path, "localhost:" + port, origin(), address)) {
                    assertEquals(429, third.status(), "both places are taken, attempt " + i);
                }

                binary.sendFrame(OPCODE_BINARY, new byte[] {1});

                assertEquals(CloseStatus.NOT_ACCEPTABLE.getCode(), binary.readUntilCloseFrame(), "attempt " + i);
                assertEquals(END_OF_STREAM, binary.nextByteWithin(Duration.ofSeconds(3)),
                        "dropped right after the close frame, without waiting for the client's reply");
            }

            // Each of the six gave its place back exactly once: one is free again, and only one.
            open.add(handshake(path, "localhost:" + port, origin(), address));
            assertEquals(101, open.getLast().status());
            try (Handshake over = handshake(path, "localhost:" + port, origin(), address)) {
                assertEquals(429, over.status());
            }
        } finally {
            for (Handshake handshake : open) {
                handshake.close();
            }
        }
    }

    @Test
    void aBinaryMessageGivesBackItsPlaceInTheRoomOnce() throws Exception {
        String room = "/ws/games/BNRY24";
        List<Handshake> open = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                open.add(handshake(room, "localhost:" + port, origin(), "203.0.113.12" + i));
                assertEquals(101, open.getLast().status());
            }
            try (Handshake fourth = handshake(room, "localhost:" + port, origin(), "203.0.113.129")) {
                assertEquals(429, fourth.status(), "the room is full");
            }

            Handshake binary = open.getFirst();
            binary.sendFrame(OPCODE_BINARY, new byte[] {1});
            assertEquals(CloseStatus.NOT_ACCEPTABLE.getCode(), binary.readUntilCloseFrame());
            assertEquals(END_OF_STREAM, binary.nextByteWithin(Duration.ofSeconds(3)));

            open.add(handshake(room, "localhost:" + port, origin(), "203.0.113.129"));
            assertEquals(101, open.getLast().status(), "the dropped socket's place in the room is free");
            try (Handshake fifth = handshake(room, "localhost:" + port, origin(), "203.0.113.128")) {
                assertEquals(429, fifth.status(), "and only that one");
            }
        } finally {
            for (Handshake handshake : open) {
                handshake.close();
            }
        }
    }

    /**
     * The neighbouring path Tomcat handles itself: a text message over its buffer is refused as a
     * protocol error, and Tomcat drops the connection with the close frame.
     */
    @Test
    void anOversizedTextMessageDropsTheConnectionToo() throws Exception {
        String address = "203.0.113.130";
        try (Handshake big = handshake("/ws/lobbies", "localhost:" + port, origin(), address)) {
            assertEquals(101, big.status());

            big.sendFrame(OPCODE_TEXT, "x".repeat(20_000).getBytes(StandardCharsets.US_ASCII));

            assertEquals(CloseStatus.TOO_BIG_TO_PROCESS.getCode(), big.readUntilCloseFrame());
            assertEquals(END_OF_STREAM, big.nextByteWithin(Duration.ofSeconds(3)));
        }
        List<Handshake> open = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                open.add(handshake("/ws/lobbies", "localhost:" + port, origin(), address));
                assertEquals(101, open.getLast().status(), "its place was given back");
            }
        } finally {
            for (Handshake handshake : open) {
                handshake.close();
            }
        }
    }

    private String origin() {
        return "http://localhost:" + port;
    }

    private boolean admittedWithin(String clientAddress, long timeoutMs) throws Exception {
        try (Handshake admitted = openWithin(clientAddress, timeoutMs)) {
            return admitted != null;
        }
    }

    /** A lobby socket from the address, retried until one is admitted; null if none is in time. */
    private Handshake openWithin(String clientAddress, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Handshake retry = handshake("/ws/lobbies", "localhost:" + port, origin(), clientAddress);
            if (retry.status() == 101 && retry.firstFrameOpcode() == OPCODE_TEXT) {
                return retry;
            }
            retry.close();
            Thread.sleep(100);
        }
        return null;
    }

    private Handshake handshake(String path, String host, String origin, String clientAddress, String... headers)
            throws IOException {
        return handshake(path, host, origin, clientAddress, 0, headers);
    }

    /** @param receiveBufferBytes the client's socket receive buffer, or 0 for the default */
    private Handshake handshake(String path, String host, String origin, String clientAddress,
                                int receiveBufferBytes, String... headers) throws IOException {
        Socket socket = new Socket();
        if (receiveBufferBytes > 0) {
            socket.setReceiveBufferSize(receiveBufferBytes);
        }
        socket.connect(new InetSocketAddress("localhost", port));
        socket.setSoTimeout(5_000);
        boolean customVersion = false;
        for (String header : headers) {
            customVersion |= header.startsWith("Sec-WebSocket-Version:");
        }
        StringBuilder request = new StringBuilder()
                .append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                .append(customVersion ? "" : "Sec-WebSocket-Version: 13\r\n")
                .append("Origin: ").append(origin).append("\r\n")
                .append("X-Forwarded-For: ").append(clientAddress).append("\r\n");
        for (String header : headers) {
            request.append(header).append("\r\n");
        }
        socket.getOutputStream().write(request.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        return new Handshake(socket, in, readStatus(in));
    }

    /** Reads the response head, leaving any body or frames that follow it unread. */
    private static int readStatus(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int last4 = 0;
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            last4 = (last4 << 8) | b;
            if (last4 == 0x0D0A0D0A) {
                break;
            }
        }
        String statusLine = head.toString(StandardCharsets.US_ASCII).lines().findFirst().orElse("");
        return Integer.parseInt(statusLine.split(" ")[1]);
    }

    /** A client connection; it reads only when a test asks and never sends anything after the request. */
    private record Handshake(Socket socket, DataInputStream in, int status) implements AutoCloseable {
        /** Sends one frame masked as clients must, with an all-zero mask so the payload goes as is. */
        void sendFrame(int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x80 | opcode);
            if (payload.length < 126) {
                frame.write(0x80 | payload.length);
            } else {
                frame.write(0x80 | 126);
                frame.write(payload.length >>> 8);
                frame.write(payload.length & 0xFF);
            }
            frame.write(new byte[4]);
            frame.write(payload);
            socket.getOutputStream().write(frame.toByteArray());
        }

        /** The opcode of the first frame the server sends, or -1 if none arrives in time. */
        int firstFrameOpcode() throws IOException {
            try {
                int first = in.read();
                return first < 0 ? -1 : first & 0x0F;
            } catch (SocketTimeoutException timeout) {
                return -1;
            }
        }

        /** True when the server ends the connection (reading past any response body) within the timeout. */
        boolean closedByServerWithin(Duration timeout) throws IOException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0) {
                    return false;
                }
                int next = nextByteWithin(Duration.ofMillis(remainingMs));
                if (next == END_OF_STREAM) {
                    return true;
                }
                if (next == STILL_OPEN) {
                    return false;
                }
            }
        }

        /** The next byte; END_OF_STREAM once the server has closed the connection, STILL_OPEN if nothing came. */
        int nextByteWithin(Duration timeout) throws IOException {
            socket.setSoTimeout((int) Math.max(1, timeout.toMillis()));
            try {
                return in.read();
            } catch (SocketTimeoutException timedOut) {
                return STILL_OPEN;
            } catch (IOException reset) {
                return END_OF_STREAM;
            }
        }

        /**
         * Skips the server's frames (which are never masked) up to a close frame and returns its
         * status code, or END_OF_STREAM if the connection ends first.
         */
        int readUntilCloseFrame() throws IOException {
            socket.setSoTimeout(10_000);
            while (true) {
                int first = in.read();
                if (first < 0) {
                    return END_OF_STREAM;
                }
                long length = in.readUnsignedByte() & 0x7F;
                if (length == 126) {
                    length = in.readUnsignedShort();
                } else if (length == 127) {
                    length = in.readLong();
                }
                if ((first & 0x0F) == OPCODE_CLOSE) {
                    int code = length >= 2 ? in.readUnsignedShort() : 1005;
                    in.skipNBytes(Math.max(0, length - 2));
                    return code;
                }
                in.skipNBytes(length);
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
