package com.example.durakgame.ratelimit;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitFilterTest {

    @Test
    void bothRoomCreationEndpointsUseTheStrictCreationBucket() throws Exception {
        for (String path : new String[]{
                "/api/games",
                "/api/games/quick-play",
                "/api/games;source=bot",
                "/api;version=1/games",
                "/api/%67ames",
                "/api/games/%71uick-play",
                "/api;version=1/%67ames"
        }) {
            RateLimitFilter filter = new RateLimitFilter(true, 100, 100, 1, 0);
            AtomicInteger chainCalls = new AtomicInteger();
            FilterChain chain = (request, response) -> chainCalls.incrementAndGet();

            MockHttpServletResponse first = filter(filter, path, chain);
            MockHttpServletResponse second = filter(filter, path, chain);

            assertEquals(200, first.getStatus(), path);
            assertEquals(429, second.getStatus(), path);
            assertTrue(second.getContentAsString().contains("Too many games created"), path);
            assertEquals(1, chainCalls.get(), path);
        }
    }

    @Test
    void spoofedForwardedForEntriesCannotMintFreshBuckets() throws Exception {
        // Same caller, a different forged leftmost entry on every request; Cloud Run appends the real peer.
        RateLimitFilter filter = new RateLimitFilter(true, 100, 100, 3, 0);
        AtomicInteger chainCalls = new AtomicInteger();
        int throttled = 0;
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/games");
            request.setRemoteAddr("169.254.1.1");
            request.addHeader("X-Forwarded-For", "10.0.0." + i + ", 198.51.100.7");
            MockHttpServletResponse response = new MockHttpServletResponse();
            // Run behind Spring's forwarded-header handling exactly as in production.
            new ForwardedHeaderFilter().doFilter(request, response,
                    (req, res) -> filter.doFilter(req, res, (r, s) -> chainCalls.incrementAndGet()));
            if (response.getStatus() == 429) {
                throttled++;
            }
        }

        assertEquals(3, chainCalls.get(), "only the creation burst may pass");
        assertEquals(7, throttled);
    }

    @Test
    void clientIsTheConfiguredHopCountedFromTheRight() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/lobbies");
        request.setRemoteAddr("169.254.1.1");
        request.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2");
        request.addHeader("X-Forwarded-For", "3.3.3.3");

        assertEquals("3.3.3.3", new RateLimitFilter(true, 1, 1, 1, 1, 1).clientKey(request));
        assertEquals("2.2.2.2", new RateLimitFilter(true, 1, 1, 1, 1, 2).clientKey(request));
        assertEquals("169.254.1.1", new RateLimitFilter(true, 1, 1, 1, 1, 0).clientKey(request));
    }

    @Test
    void peerAddressIsUsedWithoutForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/lobbies");
        request.setRemoteAddr("203.0.113.5");

        assertEquals("203.0.113.5", new RateLimitFilter(true, 1, 1, 1, 1).clientKey(request));
    }

    @Test
    void ipv6ClientsShareABucketPerSlash64() {
        String first = RateLimitFilter.bucketKey("2001:db8:1:2:aaaa::1");
        String sameSubnet = RateLimitFilter.bucketKey("2001:db8:1:2:bbbb:cccc:dddd:2");
        String otherSubnet = RateLimitFilter.bucketKey("2001:db8:1:3::1");

        assertEquals(first, sameSubnet);
        assertNotEquals(first, otherSubnet);
        assertEquals("203.0.113.5", RateLimitFilter.bucketKey("203.0.113.5"));
        // Not an IP literal: kept as an opaque, length-bounded key (never resolved via DNS).
        assertEquals("not-an-ip.example", RateLimitFilter.bucketKey("not-an-ip.example"));
    }

    private static MockHttpServletResponse filter(
            RateLimitFilter filter,
            String path,
            FilterChain chain
    ) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);
        return response;
    }
}
