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
    private record StoredGame(Game game, Map<String, Long> receipts) {}

    private final Map<String, StoredGame> games = new ConcurrentHashMap<>();

    @Override
    public void save(Game game) {
        save(game, null);
    }

    @Override
    public void saveWithReceipt(Game game, String receiptId) {
        WriteReceipts.requireId(receiptId);
        save(game, receiptId);
    }

    private void save(Game game, String receiptId) {
        // Taken from the caller's object: the copy below reports the version being written instead.
        long expectedVersion = game.getLoadedVersion();
        Game stored = copyOf(game);
        games.compute(stored.getCode(), (code, existing) -> {
            Map<String, Long> receipts = existing == null ? Map.of() : existing.receipts();
            if (receiptId != null && receipts.containsKey(receiptId)) {
                return existing;
            }
            boolean matches = expectedVersion == Game.NOT_PERSISTED
                    ? existing == null
                    : existing != null && existing.game().getVersion() == expectedVersion;
            if (!matches) {
                throw new StaleGameWriteException(code, expectedVersion,
                        existing == null ? null : existing.game().getVersion());
            }
            return new StoredGame(stored, WriteReceipts.merge(receipts, receiptId, System.currentTimeMillis()));
        });
        game.markPersisted();
    }

    @Override
    public boolean hasWriteReceipt(String code, String receiptId) {
        WriteReceipts.requireId(receiptId);
        StoredGame stored = games.get(code);
        return stored != null && stored.receipts().containsKey(receiptId);
    }

    @Override
    public Optional<Game> findByCode(String code) {
        return Optional.ofNullable(games.get(code)).map(stored -> copyOf(stored.game()));
    }

    @Override
    public void deleteByCode(String code) {
        games.remove(code);
    }

    /**
     * Removes the game only while the stored copy is still at {@code version}, atomically with
     * {@link #save}. A cleanup decision made on an older copy then cannot remove a game that was
     * saved (e.g. renewed by a heartbeat) in the meantime. Returns true when the game was removed.
     */
    public boolean deleteIfVersion(String code, long version) {
        boolean[] removed = {false};
        games.computeIfPresent(code, (key, stored) -> {
            if (stored.game().getVersion() != version) {
                return stored;
            }
            removed[0] = true;
            return null;
        });
        return removed[0];
    }

    @Override
    public boolean existsByCode(String code) {
        return games.containsKey(code);
    }

    @Override
    public Collection<Game> listAll() {
        return games.values().stream().map(stored -> copyOf(stored.game())).toList();
    }

    private static Game copyOf(Game game) {
        return Game.fromSnapshot(game.toSnapshot());
    }
}
