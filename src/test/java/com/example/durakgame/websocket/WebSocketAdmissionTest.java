package com.example.durakgame.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.server.HandshakeFailureException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketAdmissionTest {
    private final AtomicLong nanoTime = new AtomicLong();
    /** At most 3 sockets in all, 2 per client address, 2 per room. */
    private final WebSocketAdmission admission = new WebSocketAdmission(3, 2, 2, 1, nanoTime::get);

    @Test
    void refusesGameSocketsForAnythingThatCannotBeARoomCode() {
        for (String path : new String[] {"/ws/games/", "/ws/games/ABC12", "/ws/games/ABCDEFG",
                "/ws/games/ABC1O0", "/ws/games/%3Cscript%3E"}) {
            Handshake handshake = new Handshake(path, "198.51.100.1");

            assertFalse(handshake.begin(), path);
            assertEquals(404, handshake.response.getStatus(), path);
        }
        assertEquals(0, admission.openSessions());
    }

    @Test
    void acceptsRoomCodesInEitherCaseAndTheLobbySocket() {
        assertTrue(new Handshake("/ws/games/ABCD23", "198.51.100.1").begin());
        assertTrue(new Handshake("/ws/games/abcd23", "198.51.100.2").begin());
        assertTrue(new Handshake("/ws/lobbies", "198.51.100.3").begin());
    }

    @Test
    void recordsTheClientAddressTheProxyAppended() {
        Handshake handshake = new Handshake("/ws/lobbies", "203.0.113.66, 198.51.100.7");

        assertTrue(handshake.begin());

        // The leftmost entry is whatever the client sent; Cloud Run appends the real address last.
        assertEquals("198.51.100.7", handshake.reservation().client());
    }

    @Test
    void refusesHandshakesOverACapWithHttpBeforeAnyUpgrade() {
        assertTrue(new Handshake("/ws/lobbies", "198.51.100.1").upgrade());
        assertTrue(new Handshake("/ws/lobbies", "198.51.100.1").upgrade());

        Handshake overClientCap = new Handshake("/ws/lobbies", "198.51.100.1");
        assertFalse(overClientCap.begin(), "per-address cap");
        assertEquals(429, overClientCap.response.getStatus());
        assertEquals("close", overClientCap.response.getHeader("Connection"));
        assertNull(overClientCap.attributes.get(WebSocketAdmission.RESERVATION_ATTRIBUTE));

        assertTrue(new Handshake("/ws/lobbies", "198.51.100.2").upgrade());
        Handshake overTotalCap = new Handshake("/ws/lobbies", "198.51.100.3");
        assertFalse(overTotalCap.begin(), "total cap");
        assertEquals(503, overTotalCap.response.getStatus());
        assertEquals("close", overTotalCap.response.getHeader("Connection"));

        assertEquals(3, admission.openSessions());
    }

    @Test
    void capsSocketsPerRoom() {
        assertTrue(new Handshake("/ws/games/ABCD23", "198.51.100.1").upgrade());
        assertTrue(new Handshake("/ws/games/abcd23", "198.51.100.2").upgrade());

        Handshake overRoomCap = new Handshake("/ws/games/ABCD23", "198.51.100.3");
        assertFalse(overRoomCap.begin(), "per-room cap, whatever the case of the code");
        assertEquals(429, overRoomCap.response.getStatus());
        assertTrue(new Handshake("/ws/games/WXYZ23", "198.51.100.3").upgrade(), "another room");
    }

    @Test
    void aHandshakeThatDoesNotUpgradeGivesBackItsPlace() {
        // A later check (the origin check answers 403) or the upgrade itself (400, 426) refused it:
        // Spring then calls afterHandshake without an exception.
        for (int status : new int[] {403, 400, 426, 200}) {
            Handshake refused = new Handshake("/ws/games/ABCD23", "198.51.100.1");
            assertTrue(refused.begin());
            refused.response.setStatus(status);
            refused.end(null);
            assertEquals(0, admission.openSessions(), "after HTTP " + status);
        }
        Handshake failed = new Handshake("/ws/games/ABCD23", "198.51.100.1");
        assertTrue(failed.begin());
        failed.end(new HandshakeFailureException("upgrade failed"));
        assertEquals(0, admission.openSessions(), "after an exception");

        // Nothing leaked: the address and the room still have both places.
        assertTrue(new Handshake("/ws/games/ABCD23", "198.51.100.1").upgrade());
        assertTrue(new Handshake("/ws/games/ABCD23", "198.51.100.1").upgrade());
    }

    @Test
    void anUpgradedSocketKeepsItsPlaceUntilItsConnectionCloses() {
        Handshake upgraded = new Handshake("/ws/lobbies", "198.51.100.1");
        assertTrue(upgraded.upgrade());
        TestWebSocketSession session = upgraded.session();

        assertTrue(admission.opened(session));
        assertEquals(1, admission.openSessions());

        admission.release(session);
        admission.release(session);
        upgraded.end(null);

        assertEquals(0, admission.openSessions(), "released once, however many times it is called");
        assertTrue(new Handshake("/ws/lobbies", "198.51.100.1").upgrade());
        assertTrue(new Handshake("/ws/lobbies", "198.51.100.1").upgrade());
        assertFalse(new Handshake("/ws/lobbies", "198.51.100.1").begin(), "still two per address");
    }

    @Test
    void anUpgradeThatNeverOpensLosesItsPlaceAfterTheGracePeriod() {
        Handshake upgraded = new Handshake("/ws/lobbies", "198.51.100.1");
        assertTrue(upgraded.upgrade());

        nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(WebSocketAdmission.OPEN_GRACE_SECONDS) - 1);
        assertEquals(1, admission.openSessions());
        nanoTime.addAndGet(2);
        assertEquals(0, admission.openSessions());

        assertFalse(admission.opened(upgraded.session()), "too late: the caller closes it");
        admission.release(upgraded.session());
        assertEquals(0, admission.openSessions());
    }

    @Test
    void anOpenSocketIsNeverReclaimed() {
        Handshake upgraded = new Handshake("/ws/lobbies", "198.51.100.1");
        assertTrue(upgraded.upgrade());
        assertTrue(admission.opened(upgraded.session()));

        nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(10 * WebSocketAdmission.OPEN_GRACE_SECONDS));

        assertEquals(1, admission.openSessions());
    }

    @Test
    void sessionsThatSkippedTheHandshakeAreNotTracked() {
        TestWebSocketSession direct = new TestWebSocketSession("/ws/lobbies");

        assertTrue(admission.opened(direct));
        admission.release(direct);

        assertEquals(0, admission.openSessions());
    }

    /** One handshake through the admission, as Spring drives it. */
    private final class Handshake {
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final Map<String, Object> attributes = new HashMap<>();
        private final ServletServerHttpRequest request;
        private final ServletServerHttpResponse serverResponse = new ServletServerHttpResponse(response);

        Handshake(String path, String forwardedFor) {
            MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", path);
            servletRequest.addHeader("X-Forwarded-For", forwardedFor);
            servletRequest.setRemoteAddr("169.254.1.1");
            this.request = new ServletServerHttpRequest(servletRequest);
        }

        boolean begin() {
            boolean admitted = admission.beforeHandshake(request, serverResponse, null, attributes);
            if (!admitted) {
                // Spring then writes the refusal's headers, as it does after every handshake.
                serverResponse.close();
            }
            return admitted;
        }

        void end(Exception failure) {
            admission.afterHandshake(request, serverResponse, null, failure);
        }

        /** Begins and, if admitted, completes the upgrade the way Tomcat does (status 101). */
        boolean upgrade() {
            if (!begin()) {
                return false;
            }
            response.setStatus(101);
            end(null);
            return true;
        }

        WebSocketAdmission.Reservation reservation() {
            return (WebSocketAdmission.Reservation) attributes.get(WebSocketAdmission.RESERVATION_ATTRIBUTE);
        }

        TestWebSocketSession session() {
            TestWebSocketSession session = new TestWebSocketSession(request.getURI().getPath());
            session.attributes.putAll(attributes);
            return session;
        }
    }
}
