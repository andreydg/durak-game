package com.example.durakgame.service.store;

import com.example.durakgame.model.Game;

/**
 * Thrown when a save does not find the stored version its game was loaded from, because another
 * writer saved, created or deleted the game in between (lost-update protection).
 */
public class StaleGameWriteException extends IllegalStateException {
    public StaleGameWriteException(String code, long expectedVersion, long storedVersion) {
        this(code, expectedVersion, Long.valueOf(storedVersion));
    }

    /**
     * {@code expectedVersion} is {@link Game#NOT_PERSISTED} when the save creates the game;
     * {@code storedVersion} is null when nothing is stored under the code.
     */
    public StaleGameWriteException(String code, long expectedVersion, Long storedVersion) {
        super("Stale write rejected for game " + code
                + ": expected stored version " + (expectedVersion == Game.NOT_PERSISTED ? "none" : expectedVersion)
                + " but found " + (storedVersion == null ? "none" : storedVersion));
    }
}
