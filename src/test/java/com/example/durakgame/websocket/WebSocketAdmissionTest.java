package com.example.durakgame.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketAdmissionTest {
    private final WebSocketAdmission admission = new WebSocketAdmission(3, 2, 1);

    @Test
    void refusesGameSocketsForAnythingThatCannotBeARoomCode() {
        for (String path : new String[] {"/ws/games/", "/ws/games/ABC12", "/ws/games/ABCDEFG",
                "/ws/games/ABC1O0", "/ws/games/%3Cscript%3E"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean accepted = admission.beforeHandshake(request(path), new ServletServerHttpResponse(response),
                    null, new HashMap<>());

            assertFalse(accepted, path);
            assertEquals(404, response.getStatus(), path);
        }
    }

    @Test
    void acceptsRoomCodesInEitherCaseAndTheLobbySocket() {
        assertTrue(handshake("/ws/games/ABCD23", new HashMap<>()));
        assertTrue(handshake("/ws/games/abcd23", new HashMap<>()));
        assertTrue(handshake("/ws/lobbies", new HashMap<>()));
    }

    @Test
    void recordsTheClientAddressTheProxyAppended() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/lobbies");
        request.addHeader("X-Forwarded-For", "203.0.113.66, 198.51.100.7");
        request.setRemoteAddr("169.254.1.1");
        Map<String, Object> attributes = new HashMap<>();

        admission.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()), null, attributes);

        // The leftmost entry is whatever the client sent; Cloud Run appends the real address last.
        assertEquals("198.51.100.7", attributes.get(WebSocketAdmission.CLIENT_KEY_ATTRIBUTE));
    }

    @Test
    void capsSessionsPerClientAndInTotal() {
        TestWebSocketSession a1 = new TestWebSocketSession("/ws/lobbies", "198.51.100.1");
        TestWebSocketSession a2 = new TestWebSocketSession("/ws/lobbies", "198.51.100.1");
        TestWebSocketSession a3 = new TestWebSocketSession("/ws/lobbies", "198.51.100.1");
        TestWebSocketSession b1 = new TestWebSocketSession("/ws/lobbies", "198.51.100.2");
        TestWebSocketSession c1 = new TestWebSocketSession("/ws/lobbies", "198.51.100.3");

        assertTrue(admission.admit(a1));
        assertTrue(admission.admit(a2));
        assertFalse(admission.admit(a3), "per-client cap");
        assertTrue(admission.admit(b1));
        assertFalse(admission.admit(c1), "total cap");

        admission.release(a1);
        admission.release(a1);
        admission.release(a3);

        // a1 left (released twice: still one slot); a3 was never admitted, so it freed nothing.
        assertEquals(2, admission.openSessions());
        assertTrue(admission.admit(a3), "a1's slot is free again");
        assertFalse(admission.admit(c1), "total cap again");
    }

    private boolean handshake(String path, Map<String, Object> attributes) {
        return admission.beforeHandshake(request(path),
                new ServletServerHttpResponse(new MockHttpServletResponse()), null, attributes);
    }

    private static ServletServerHttpRequest request(String path) {
        return new ServletServerHttpRequest(new MockHttpServletRequest("GET", path));
    }
}
