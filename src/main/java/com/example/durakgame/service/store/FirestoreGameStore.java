package com.example.durakgame.service.store;

import com.example.durakgame.model.Game;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.Timestamp;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@Lazy
public class FirestoreGameStore implements GameStore {
    private static final Logger log = LoggerFactory.getLogger(FirestoreGameStore.class);
    private static final String COLLECTION = "games";
    private static final String FIELD_PAYLOAD = "payload";
    private static final String FIELD_EXPIRE_AT = "expireAt";
    private static final Duration GAME_TTL = Duration.ofHours(24);
    private final Firestore firestore;

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
        String encoded = encode(game);
        try {
            Instant expireAt = game.getCreatedAt().plus(GAME_TTL);
            Timestamp expireTs = Timestamp.ofTimeSecondsAndNanos(expireAt.getEpochSecond(), expireAt.getNano());
            log.info("firestore_write code={} op=save", game.getCode());
            collection().document(game.getCode())
                    .set(Map.of(
                            FIELD_PAYLOAD, encoded,
                            FIELD_EXPIRE_AT, expireTs
                    ))
                    .get();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to save game to Firestore", ex);
        }
    }

    @Override
    public Optional<Game> findByCode(String code) {
        try {
            log.info("firestore_read code={} op=findByCode", code);
            DocumentSnapshot snapshot = collection().document(code).get().get();
            if (!snapshot.exists()) {
                return Optional.empty();
            }
            return Optional.of(decode(snapshot.getString(FIELD_PAYLOAD)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read game from Firestore", ex);
        }
    }

    @Override
    public void deleteByCode(String code) {
        try {
            log.info("firestore_write code={} op=delete", code);
            collection().document(code).delete().get();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to delete game from Firestore", ex);
        }
    }

    @Override
    public boolean existsByCode(String code) {
        try {
            log.info("firestore_read code={} op=existsByCode", code);
            return collection().document(code).get().get().exists();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to check game existence in Firestore", ex);
        }
    }

    @Override
    public List<Game> listAll() {
        try {
            log.info("firestore_read op=listAll");
            return collection().get().get().getDocuments().stream()
                    .map(doc -> decode(doc.getString(FIELD_PAYLOAD)))
                    .toList();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to list games from Firestore", ex);
        }
    }

    private CollectionReference collection() {
        return firestore.collection(COLLECTION);
    }

    private String encode(Game game) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(game);
            out.flush();
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize game", ex);
        }
    }

    private Game decode(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalStateException("Missing persisted payload");
        }
        byte[] bytes = Base64.getDecoder().decode(payload);
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
             ObjectInputStream in = new ObjectInputStream(bis)) {
            return (Game) in.readObject();
        } catch (IOException | ClassNotFoundException ex) {
            throw new IllegalStateException("Failed to deserialize game", ex);
        }
    }
}
