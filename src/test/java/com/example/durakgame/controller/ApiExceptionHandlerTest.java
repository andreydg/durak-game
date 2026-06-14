package com.example.durakgame.controller;

import com.example.durakgame.service.store.StaleGameWriteException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void notFoundMapsTo404() {
        ResponseEntity<Map<String, Object>> res =
                handler.handleNotFound(new NoSuchElementException("Game not found"));
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        assertEquals("Game not found", body(res).get("message"));
        assertEquals(404, body(res).get("status"));
    }

    @Test
    void illegalStateMapsTo409() {
        ResponseEntity<Map<String, Object>> res =
                handler.handleIllegalState(new IllegalStateException("Defender cannot attack"));
        assertEquals(HttpStatus.CONFLICT, res.getStatusCode());
        assertEquals("Defender cannot attack", body(res).get("message"));
    }

    @Test
    void illegalArgumentMapsTo400() {
        ResponseEntity<Map<String, Object>> res =
                handler.handleIllegalArgument(new IllegalArgumentException("Name too short"));
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertEquals("Name too short", body(res).get("message"));
    }

    @Test
    void staleWriteMapsTo409WithFriendlyMessage() {
        ResponseEntity<Map<String, Object>> res =
                handler.handleStaleWrite(new StaleGameWriteException("ABC123", 1, 2));
        assertEquals(HttpStatus.CONFLICT, res.getStatusCode());
        assertEquals("Game state changed — please retry your move.", body(res).get("message"));
    }

    private static Map<String, Object> body(ResponseEntity<Map<String, Object>> res) {
        Map<String, Object> body = res.getBody();
        if (body == null) {
            throw new AssertionError("expected error body");
        }
        return body;
    }
}
