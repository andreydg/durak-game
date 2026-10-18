package com.example.durakgame.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real handshakes against the running server, written by hand so the test controls every header
 * a browser or Cloud Run's front end would send. Each test uses its own X-Forwarded-For address
 * (the proxy-appended entry is the client key), so their sockets don't share a cap.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.websocket.max-sessions-per-client=2",
        "autoplay.gemini.enabled=false"
})
class WebSocketHandshakeIntegrationTest {
    private static final int OPCODE_TEXT = 0x1;
    private static final int OPCODE_CLOSE = 0x8;

    @Value("${local.server.port}")
    private int port;

    @Test
    void theSiteItselfMayOpenSockets() throws IOException {
        try (Handshake handshake = handshake("/ws/lobbies", "localhost:" + port, "http://localhost:" + port, "198.51.100.1")) {
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
        String origin = "http://localhost:" + port;
        try (Handshake invented = handshake("/ws/games/not-a-room", "localhost:" + port, origin, "198.51.100.4")) {
            assertEquals(404, invented.status());
        }
        try (Handshake room = handshake("/ws/games/ABCD23", "localhost:" + port, origin, "198.51.100.4")) {
            assertEquals(101, room.status());
        }
    }

    @Test
    void oneAddressHoldsOnlyALimitedNumberOfSockets() throws Exception {
        String origin = "http://localhost:" + port;
        List<Handshake> open = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                Handshake admitted = handshake("/ws/lobbies", "localhost:" + port, origin, "203.0.113.50");
                open.add(admitted);
                assertEquals(OPCODE_TEXT, admitted.firstFrameOpcode());
            }
            try (Handshake surplus = handshake("/ws/lobbies", "localhost:" + port, origin, "203.0.113.50")) {
                assertEquals(101, surplus.status());
                assertEquals(OPCODE_CLOSE, surplus.firstFrameOpcode(), "the third socket is closed straight away");
            }
            try (Handshake otherAddress = handshake("/ws/lobbies", "localhost:" + port, origin, "203.0.113.51")) {
                assertEquals(OPCODE_TEXT, otherAddress.firstFrameOpcode());
            }

            open.removeFirst().close();
            assertTrue(admittedWithin(origin, "203.0.113.50", 3_000), "a closed socket frees its slot");
        } finally {
            for (Handshake handshake : open) {
                handshake.close();
            }
        }
    }

    private boolean admittedWithin(String origin, String clientAddress, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Handshake retry = handshake("/ws/lobbies", "localhost:" + port, origin, clientAddress)) {
                if (retry.firstFrameOpcode() == OPCODE_TEXT) {
                    return true;
                }
            }
            Thread.sleep(100);
        }
        return false;
    }

    private Handshake handshake(String path, String host, String origin, String clientAddress, String... headers)
            throws IOException {
        Socket socket = new Socket("localhost", port);
        socket.setSoTimeout(5_000);
        StringBuilder request = new StringBuilder()
                .append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                .append("Sec-WebSocket-Version: 13\r\n")
                .append("Origin: ").append(origin).append("\r\n")
                .append("X-Forwarded-For: ").append(clientAddress).append("\r\n");
        for (String header : headers) {
            request.append(header).append("\r\n");
        }
        socket.getOutputStream().write(request.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
        return new Handshake(socket, readStatus(socket.getInputStream()));
    }

    /** Reads the response head byte by byte, leaving any frames that follow it unread. */
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

    private record Handshake(Socket socket, int status) implements AutoCloseable {
        /** The opcode of the first frame the server sends, or -1 if none arrives in time. */
        int firstFrameOpcode() throws IOException {
            try {
                int first = socket.getInputStream().read();
                return first < 0 ? -1 : first & 0x0F;
            } catch (SocketTimeoutException timeout) {
                return -1;
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
