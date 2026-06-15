package com.example.durakgame.service.store;

/**
 * Signals that the backing store failed for an infrastructure reason (read/write/network/format)
 * rather than a game-rule conflict. Deliberately does NOT extend {@code IllegalStateException} so
 * the API maps it to a 5xx ("try again later"), not a 409 ("your move conflicts").
 */
public class GameStoreException extends RuntimeException {
    public GameStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public GameStoreException(String message) {
        super(message);
    }
}
