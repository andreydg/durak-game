package com.example.durakgame.service.store;

/** Thrown when a save would overwrite a newer version of the same game (lost-update protection). */
public class StaleGameWriteException extends IllegalStateException {
    public StaleGameWriteException(String code, long attemptedVersion, long storedVersion) {
        super("Stale write rejected for game " + code
                + ": attempted version " + attemptedVersion
                + " but stored version is " + storedVersion);
    }
}
