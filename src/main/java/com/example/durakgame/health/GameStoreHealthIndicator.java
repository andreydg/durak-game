package com.example.durakgame.health;

import com.example.durakgame.service.store.GameStore;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Reports DOWN when the backing game store is unreachable, so the platform health check
 * reflects real readiness (the previous "/" ping stayed 200 even if Firestore was down).
 * The reachability probe result is cached briefly because health is polled frequently and a
 * Firestore round-trip per ping would add cost.
 */
@Component("gameStore")
public class GameStoreHealthIndicator implements HealthIndicator {
    private static final long CACHE_TTL_MS = 10_000;
    /*
     * A code never allocated as a real game (those are 6 uppercase alphanumerics), so the probe is
     * a cheap negative lookup. Must avoid Firestore's reserved "__.*__" document-id pattern, which
     * is rejected on read — hence the hyphen rather than wrapping underscores.
     */
    private static final String PROBE_CODE = "health-probe";

    private final GameStore gameStore;
    private final AtomicReference<CachedHealth> cached = new AtomicReference<>();

    private record CachedHealth(long atMs, Health health) {
    }

    public GameStoreHealthIndicator(GameStore gameStore) {
        this.gameStore = gameStore;
    }

    @Override
    public Health health() {
        CachedHealth snapshot = cached.get();
        long now = System.currentTimeMillis();
        if (snapshot != null && now - snapshot.atMs() < CACHE_TTL_MS) {
            return snapshot.health();
        }
        Health health = probe();
        cached.set(new CachedHealth(now, health));
        return health;
    }

    private Health probe() {
        try {
            gameStore.existsByCode(PROBE_CODE);
            return Health.up().build();
        } catch (RuntimeException ex) {
            return Health.down(ex).build();
        }
    }
}
