package com.example.durakgame.service.store;

import com.example.durakgame.model.Game;

import java.util.Collection;
import java.util.Optional;

public interface GameStore {
    void save(Game game);

    Optional<Game> findByCode(String code);

    void deleteByCode(String code);

    boolean existsByCode(String code);

    Collection<Game> listAll();
}
