package com.example.durakgame.service.store;

import java.util.List;
import java.time.Instant;

/**
 * Lightweight lobby summary the store can return without decoding the full game payload.
 * {@code maxPlayers} is intentionally absent — it's a service-level policy, not stored state.
 */
public record LobbyProjection(
        String code,
        String hostName,
        List<String> playerNames,
        int playerCount,
        Instant lastActivityAt
) {
}
