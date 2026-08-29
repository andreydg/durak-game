package com.example.durakgame.service;

/** Publishes a lightweight invalidation when the public lobby projection may have changed. */
@FunctionalInterface
public interface LobbyUpdatePublisher {
    void lobbiesChanged();

    static LobbyUpdatePublisher noop() {
        return () -> { };
    }
}
