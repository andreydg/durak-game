package com.example.durakgame.service;

/** The room existed but exceeded the inactivity policy and can no longer be joined or resumed. */
public class RoomExpiredException extends RuntimeException {
    public RoomExpiredException() {
        super("Room expired due to inactivity.");
    }
}
