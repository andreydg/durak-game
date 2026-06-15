package com.example.durakgame.service;

/**
 * Thrown when a request does not present the secret token that proves ownership of the
 * acting/viewing player. Maps to HTTP 403 so impersonation attempts are rejected.
 */
public class UnauthorizedActionException extends RuntimeException {
    public UnauthorizedActionException() {
        super("You are not authorized to act as this player.");
    }
}
