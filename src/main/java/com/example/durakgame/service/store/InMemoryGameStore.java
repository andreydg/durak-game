package com.example.durakgame.service.store;

import com.example.durakgame.model.Game;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryGameStore implements GameStore {
    private final Map<String, Game> games = new ConcurrentHashMap<>();

    @Override
    public void save(Game game) {
        games.merge(game.getCode(), game, (existing, incoming) -> {
            if (existing != incoming && existing.getVersion() >= incoming.getVersion()) {
                throw new StaleGameWriteException(incoming.getCode(), incoming.getVersion(), existing.getVersion());
            }
            return incoming;
        });
    }

    @Override
    public Optional<Game> findByCode(String code) {
        return Optional.ofNullable(games.get(code));
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
        return games.values();
    }
}
