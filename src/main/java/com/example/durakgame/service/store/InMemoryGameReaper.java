package com.example.durakgame.service.store;

import com.example.durakgame.model.Game;
import com.example.durakgame.service.GameExpiryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Periodically evicts stale games from the in-memory store so abandoned lobbies (closed tabs
 * whose leave-beacon never fired) don't leak memory. Operates directly on {@link InMemoryGameStore}:
 * on Cloud Run that store stays empty (Firestore is active and has its own TTL), so this is a no-op
 * there. The same activity-based policy is enforced synchronously by the API and lobby list.
 */
@Component
public class InMemoryGameReaper {
    private static final Logger log = LoggerFactory.getLogger(InMemoryGameReaper.class);

    private final InMemoryGameStore store;
    private final GameExpiryPolicy expiryPolicy;

    public InMemoryGameReaper(
            InMemoryGameStore store,
            GameExpiryPolicy expiryPolicy
    ) {
        this.store = store;
        this.expiryPolicy = expiryPolicy;
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

    boolean shouldReap(Game game, Instant now) {
        return expiryPolicy.isExpired(game, now);
    }
}
