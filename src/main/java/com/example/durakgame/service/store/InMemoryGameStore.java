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
        Game stored = copyOf(game);
        games.merge(stored.getCode(), stored, (existing, incoming) -> {
            if (existing.getVersion() >= incoming.getVersion()) {
                throw new StaleGameWriteException(incoming.getCode(), incoming.getVersion(), existing.getVersion());
            }
            return incoming;
        });
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
