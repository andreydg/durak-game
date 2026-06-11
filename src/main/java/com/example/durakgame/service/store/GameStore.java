package com.example.durakgame.service.store;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;

import java.util.Collection;
import java.util.Optional;

public interface GameStore {
    /**
     * Persists the game. Implementations reject stale writes: saving a game whose
     * {@code version} is not newer than the stored one throws {@link StaleGameWriteException}.
     */
    void save(Game game);

    Optional<Game> findByCode(String code);

    void deleteByCode(String code);

    boolean existsByCode(String code);

    Collection<Game> listAll();

    /** Games still waiting in the lobby; cheaper than {@link #listAll()} where the backend supports filtering. */
    default Collection<Game> listOpenLobbies() {
        return listAll().stream()
                .filter(game -> game.getStatus() == GameStatus.LOBBY)
                .toList();
    }
}
