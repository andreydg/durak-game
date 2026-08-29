package com.example.durakgame.ratelimit;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitFilterTest {

    @Test
    void bothRoomCreationEndpointsUseTheStrictCreationBucket() throws Exception {
        for (String path : new String[]{
                "/api/games",
                "/api/games/quick-play",
                "/api/games;source=bot",
                "/api;version=1/games"
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
