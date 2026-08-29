package com.example.durakgame.service.store;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.service.GameExpiryPolicy;
import com.google.cloud.firestore.Blob;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.Timestamp;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
    // Denormalized lobby summary fields, so the lobby list never decodes full game payloads.
    private static final String FIELD_CODE = "code";
    private static final String FIELD_HOST_NAME = "hostName";
    private static final String FIELD_PLAYER_NAMES = "playerNames";
    private static final String FIELD_PLAYER_COUNT = "playerCount";
    private static final String FIELD_LAST_ACTIVITY_AT = "lastActivityAt";
    private static final Duration LEGACY_GAME_TTL = Duration.ofHours(24);
    private final Firestore firestore;
    private final GameExpiryPolicy expiryPolicy;
    private final GameBinaryCodec codec = new GameBinaryCodec();

    @Autowired
    public FirestoreGameStore(
            @Value("${app.firestore.database-id:(default)}") String databaseId,
            GameExpiryPolicy expiryPolicy
    ) {
        String resolvedDatabaseId = (databaseId == null || databaseId.isBlank()) ? "(default)" : databaseId.trim();
        this.expiryPolicy = expiryPolicy;
        this.firestore = FirestoreOptions.getDefaultInstance().toBuilder()
                .setDatabaseId(resolvedDatabaseId)
                .build()
                .getService();
        log.info("firestore_store_initialized databaseId={}", resolvedDatabaseId);
    }

    FirestoreGameStore(String databaseId) {
        this(databaseId, GameExpiryPolicy.defaults());
    }

    @Override
    public void save(Game game) {
        byte[] encoded = encode(game);
        long attemptedVersion = game.getVersion();
        try {
            Instant expireAt = expiryPolicy.expiresAt(game);
            Timestamp expireTs = Timestamp.ofTimeSecondsAndNanos(expireAt.getEpochSecond(), expireAt.getNano());
            Instant lastActivityAt = game.getLastActivityAt();
            Timestamp lastActivityTs = Timestamp.ofTimeSecondsAndNanos(
                    lastActivityAt.getEpochSecond(), lastActivityAt.getNano());
            log.debug("firestore_write code={} op=save version={}", game.getCode(), attemptedVersion);
            DocumentReference ref = collection().document(game.getCode());
            firestore.runTransaction(tx -> {
                DocumentSnapshot existing = tx.get(ref).get();
                Long storedVersion = existing.exists() ? existing.getLong(FIELD_VERSION) : null;
                if (storedVersion != null && storedVersion >= attemptedVersion) {
                    throw new StaleGameWriteException(game.getCode(), attemptedVersion, storedVersion);
                }
                LobbyProjection summary = GameStore.toProjection(game);
                tx.set(ref, Map.ofEntries(
                        Map.entry(FIELD_PAYLOAD, Blob.fromBytes(encoded)),
                        Map.entry(FIELD_EXPIRE_AT, expireTs),
                        Map.entry(FIELD_LAST_ACTIVITY_AT, lastActivityTs),
                        Map.entry(FIELD_STATUS, game.getStatus().name()),
                        Map.entry(FIELD_VERSION, attemptedVersion),
                        Map.entry(FIELD_CODE, game.getCode()),
                        Map.entry(FIELD_HOST_NAME, summary.hostName()),
                        Map.entry(FIELD_PLAYER_NAMES, summary.playerNames()),
                        Map.entry(FIELD_PLAYER_COUNT, summary.playerCount())
                ));
                return null;
            }).get();
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof StaleGameWriteException stale) {
                throw stale;
            }
            throw new GameStoreException("Failed to save game to Firestore", ex);
        } catch (Exception ex) {
            throw new GameStoreException("Failed to save game to Firestore", ex);
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
            throw new GameStoreException("Failed to read game from Firestore", ex);
        }
    }

    @Override
    public void deleteByCode(String code) {
        try {
            log.debug("firestore_write code={} op=delete", code);
            collection().document(code).delete().get();
        } catch (Exception ex) {
            throw new GameStoreException("Failed to delete game from Firestore", ex);
        }
    }

    @Override
    public boolean existsByCode(String code) {
        try {
            log.debug("firestore_read code={} op=existsByCode", code);
            return collection().document(code).get().get().exists();
        } catch (Exception ex) {
            throw new GameStoreException("Failed to check game existence in Firestore", ex);
        }
    }

    @Override
    public List<Game> listAll() {
        try {
            log.debug("firestore_read op=listAll");
            return decodeAll(collection().get().get().getDocuments());
        } catch (Exception ex) {
            throw new GameStoreException("Failed to list games from Firestore", ex);
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
            throw new GameStoreException("Failed to list lobby games from Firestore", ex);
        }
    }

    @Override
    public List<LobbyProjection> listOpenLobbySummaries() {
        try {
            log.debug("firestore_read op=listOpenLobbySummaries");
            List<LobbyProjection> summaries = new ArrayList<>();
            Instant now = Instant.now();
            // Project only the denormalized summary fields — no payload blob, no decode.
            for (DocumentSnapshot doc : collection()
                    .whereEqualTo(FIELD_STATUS, GameStatus.LOBBY.name())
                    .select(FIELD_CODE, FIELD_HOST_NAME, FIELD_PLAYER_NAMES, FIELD_PLAYER_COUNT,
                            FIELD_LAST_ACTIVITY_AT, FIELD_EXPIRE_AT)
                    .get().get().getDocuments()) {
                Timestamp expireAt = doc.getTimestamp(FIELD_EXPIRE_AT);
                if (expireAt != null && !toInstant(expireAt).isAfter(now)) {
                    doc.getReference().delete();
                    log.info("firestore_expired_lobby_cleanup code={}", doc.getId());
                    continue;
                }
                summaries.add(toProjection(doc));
            }
            return summaries;
        } catch (Exception ex) {
            throw new GameStoreException("Failed to list lobby summaries from Firestore", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private LobbyProjection toProjection(DocumentSnapshot doc) {
        String code = doc.getString(FIELD_CODE);
        String hostName = doc.getString(FIELD_HOST_NAME);
        List<String> playerNames = (List<String>) doc.get(FIELD_PLAYER_NAMES);
        Long playerCount = doc.getLong(FIELD_PLAYER_COUNT);
        Timestamp lastActivityAt = doc.getTimestamp(FIELD_LAST_ACTIVITY_AT);
        Timestamp expireAt = doc.getTimestamp(FIELD_EXPIRE_AT);
        Instant activity = lastActivityAt != null
                ? toInstant(lastActivityAt)
                : legacyActivityFromExpiry(expireAt);
        return new LobbyProjection(
                code != null ? code : doc.getId(),
                hostName != null ? hostName : "?",
                playerNames != null ? playerNames : List.of(),
                playerCount != null ? playerCount.intValue() : (playerNames != null ? playerNames.size() : 0),
                activity
        );
    }

    private Instant legacyActivityFromExpiry(Timestamp expireAt) {
        return expireAt == null ? Instant.EPOCH : toInstant(expireAt).minus(LEGACY_GAME_TTL);
    }

    private Instant toInstant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
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
            throw new GameStoreException("Missing persisted payload");
        }
        if (!codec.isCodecPayload(payload)) {
            throw new GameStoreException("Unsupported payload format");
        }
        return codec.decode(payload);
    }
}
