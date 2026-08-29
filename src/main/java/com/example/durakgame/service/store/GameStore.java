package com.example.durakgame.service.store;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Player;

import java.util.Collection;
import java.util.List;
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

    /**
     * Lobby summaries without decoding full game payloads. The default derives them from
     * {@link #listOpenLobbies()} (fine for in-memory); backends that store denormalized fields
     * (Firestore) override this to avoid deserializing every game on each lobby refresh.
     */
    default List<LobbyProjection> listOpenLobbySummaries() {
        return listOpenLobbies().stream()
                .map(GameStore::toProjection)
                .toList();
    }

    static LobbyProjection toProjection(Game game) {
        String hostName = game.getPlayers().stream()
                .filter(p -> p.getId().equals(game.getHostPlayerId()))
                .map(Player::getName)
                .findFirst()
                .orElse("?");
        List<String> playerNames = game.getPlayers().stream().map(Player::getName).toList();
        return new LobbyProjection(
                game.getCode(),
                hostName,
                playerNames,
                game.getPlayers().size(),
                game.getLastActivityAt()
        );
    }
}
