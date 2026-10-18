package com.example.durakgame.service.store;

import com.example.durakgame.model.Game;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps games in memory with the same semantics as the Firestore store: reads hand out independent
 * copies and only {@link #save} publishes changes. Sharing the live instance would let a request
 * that fails half-way through a mutation leak its partial changes into the stored game, and let
 * readers observe a game mid-mutation.
 */
@Component
public class InMemoryGameStore implements GameStore {
    private final Map<String, Game> games = new ConcurrentHashMap<>();

    @Override
    public void save(Game game) {
        // Taken from the caller's object: the copy below reports the version being written instead.
        long expectedVersion = game.getLoadedVersion();
        Game stored = copyOf(game);
        games.compute(stored.getCode(), (code, existing) -> {
            boolean matches = expectedVersion == Game.NOT_PERSISTED
                    ? existing == null
                    : existing != null && existing.getVersion() == expectedVersion;
            if (!matches) {
                throw new StaleGameWriteException(code, expectedVersion, existing == null ? null : existing.getVersion());
            }
            return stored;
        });
        game.markPersisted();
    }

    @Override
    public Optional<Game> findByCode(String code) {
        return Optional.ofNullable(games.get(code)).map(InMemoryGameStore::copyOf);
    }

    @Override
    public void deleteByCode(String code) {
        games.remove(code);
    }

    @Override
    public boolean existsByCode(String code) {
        return games.containsKey(code);
    }

    @Override
    public Collection<Game> listAll() {
        return games.values().stream().map(InMemoryGameStore::copyOf).toList();
    }

    private static Game copyOf(Game game) {
        return Game.fromSnapshot(game.toSnapshot());
    }
}
