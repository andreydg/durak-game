package com.example.durakgame.service.store;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.google.cloud.firestore.Blob;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.Timestamp;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

@Component
@Lazy
public class FirestoreGameStore implements GameStore {
    private static final Logger log = LoggerFactory.getLogger(FirestoreGameStore.class);
    private static final String COLLECTION = "games";
    private static final String FIELD_PAYLOAD = "payload";
    private static final String FIELD_EXPIRE_AT = "expireAt";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_VERSION = "version";
    private static final Duration GAME_TTL = Duration.ofHours(24);
    private final Firestore firestore;
    private final GameBinaryCodec codec = new GameBinaryCodec();

    public FirestoreGameStore(@Value("${app.firestore.database-id:(default)}") String databaseId) {
        String resolvedDatabaseId = (databaseId == null || databaseId.isBlank()) ? "(default)" : databaseId.trim();
        this.firestore = FirestoreOptions.getDefaultInstance().toBuilder()
                .setDatabaseId(resolvedDatabaseId)
                .build()
                .getService();
        log.info("firestore_store_initialized databaseId={}", resolvedDatabaseId);
    }

    @Override
    public void save(Game game) {
        byte[] encoded = encode(game);
        long attemptedVersion = game.getVersion();
        try {
            Instant expireAt = game.getCreatedAt().plus(GAME_TTL);
            Timestamp expireTs = Timestamp.ofTimeSecondsAndNanos(expireAt.getEpochSecond(), expireAt.getNano());
            log.debug("firestore_write code={} op=save version={}", game.getCode(), attemptedVersion);
            DocumentReference ref = collection().document(game.getCode());
            firestore.runTransaction(tx -> {
                DocumentSnapshot existing = tx.get(ref).get();
                Long storedVersion = existing.exists() ? existing.getLong(FIELD_VERSION) : null;
                if (storedVersion != null && storedVersion >= attemptedVersion) {
                    throw new StaleGameWriteException(game.getCode(), attemptedVersion, storedVersion);
                }
                tx.set(ref, Map.of(
                        FIELD_PAYLOAD, Blob.fromBytes(encoded),
                        FIELD_EXPIRE_AT, expireTs,
                        FIELD_STATUS, game.getStatus().name(),
                        FIELD_VERSION, attemptedVersion
                ));
                return null;
            }).get();
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof StaleGameWriteException stale) {
                throw stale;
            }
            throw new IllegalStateException("Failed to save game to Firestore", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to save game to Firestore", ex);
        }
    }

    @Override
    public Optional<Game> findByCode(String code) {
        try {
            log.debug("firestore_read code={} op=findByCode", code);
            DocumentSnapshot snapshot = collection().document(code).get().get();
            if (!snapshot.exists()) {
                return Optional.empty();
            }
            Blob payload = snapshot.getBlob(FIELD_PAYLOAD);
            return Optional.of(decode(payload == null ? null : payload.toBytes()));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read game from Firestore", ex);
        }
    }

    @Override
    public void deleteByCode(String code) {
        try {
            log.debug("firestore_write code={} op=delete", code);
            collection().document(code).delete().get();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to delete game from Firestore", ex);
        }
    }

    @Override
    public boolean existsByCode(String code) {
        try {
            log.debug("firestore_read code={} op=existsByCode", code);
            return collection().document(code).get().get().exists();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to check game existence in Firestore", ex);
        }
    }

    @Override
    public List<Game> listAll() {
        try {
            log.debug("firestore_read op=listAll");
            return decodeAll(collection().get().get().getDocuments());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to list games from Firestore", ex);
        }
    }

    @Override
    public List<Game> listOpenLobbies() {
        try {
            log.debug("firestore_read op=listOpenLobbies");
            return decodeAll(collection()
                    .whereEqualTo(FIELD_STATUS, GameStatus.LOBBY.name())
                    .get().get().getDocuments());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to list lobby games from Firestore", ex);
        }
    }

    private List<Game> decodeAll(List<? extends DocumentSnapshot> documents) {
        List<Game> games = new ArrayList<>();
        for (DocumentSnapshot doc : documents) {
            try {
                Blob payload = doc.getBlob(FIELD_PAYLOAD);
                games.add(decode(payload == null ? null : payload.toBytes()));
            } catch (RuntimeException ex) {
                String code = doc.getId();
                log.warn("firestore_payload_decode_failed code={} - deleting stale document", code);
                try {
                    collection().document(code).delete().get();
                } catch (Exception deleteEx) {
                    log.warn("firestore_delete_failed code={} after decode failure", code);
                }
            }
        }
        return games;
    }

    private CollectionReference collection() {
        return firestore.collection(COLLECTION);
    }

    private byte[] encode(Game game) {
        byte[] binary = codec.encode(game);
        if (log.isDebugEnabled()) {
            log.debug("firestore_payload_size code={} codecBytes={}", game.getCode(), binary.length);
        }
        return binary;
    }

    private Game decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new IllegalStateException("Missing persisted payload");
        }
        if (!codec.isCodecPayload(payload)) {
            throw new IllegalStateException("Unsupported payload format");
        }
        return codec.decode(payload);
    }
}
