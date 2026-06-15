package com.example.durakgame.service.store;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Periodically evicts stale games from the in-memory store so abandoned lobbies (closed tabs
 * whose leave-beacon never fired) don't leak memory. Operates directly on {@link InMemoryGameStore}:
 * on Cloud Run that store stays empty (Firestore is active and has its own TTL), so this is a no-op
 * there. Active games are protected up to {@code hardMaxAge}; lobbies are reaped sooner.
 */
@Component
public class InMemoryGameReaper {
    private static final Logger log = LoggerFactory.getLogger(InMemoryGameReaper.class);

    private final InMemoryGameStore store;
    private final Duration lobbyMaxAge;
    private final Duration hardMaxAge;

    public InMemoryGameReaper(
            InMemoryGameStore store,
            @Value("${app.reaper.lobby-max-age-minutes:120}") long lobbyMaxAgeMinutes,
            @Value("${app.reaper.hard-max-age-hours:24}") long hardMaxAgeHours
    ) {
        this.store = store;
        this.lobbyMaxAge = Duration.ofMinutes(lobbyMaxAgeMinutes);
        this.hardMaxAge = Duration.ofHours(hardMaxAgeHours);
    }

    @Scheduled(
            initialDelayString = "${app.reaper.interval-ms:1800000}",
            fixedDelayString = "${app.reaper.interval-ms:1800000}"
    )
    public void reap() {
        Instant now = Instant.now();
        int removed = 0;
        for (Game game : store.listAll()) {
            if (shouldReap(game, now)) {
                store.deleteByCode(game.getCode());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("inmemory_reaper_evicted count={}", removed);
        }
    }

    /** Reap a lobby past {@code lobbyMaxAge}, or any game past the {@code hardMaxAge} ceiling. */
    boolean shouldReap(Game game, Instant now) {
        Duration age = Duration.between(game.getCreatedAt(), now);
        if (age.compareTo(hardMaxAge) >= 0) {
            return true;
        }
        return game.getStatus() == GameStatus.LOBBY && age.compareTo(lobbyMaxAge) >= 0;
    }
}
