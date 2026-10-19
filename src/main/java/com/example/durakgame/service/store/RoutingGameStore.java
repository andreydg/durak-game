package com.example.durakgame.service.store;

import com.example.durakgame.model.Game;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Picks the backing store once at startup from {@code app.store}: {@code auto} (Firestore on Cloud Run,
 * detected via {@code K_SERVICE}, in-memory elsewhere), {@code firestore} or {@code memory}. If Firestore
 * is selected but unavailable, startup fails: a silent fallback to memory would lose every room on restart.
 */
@Component
@Primary
public class RoutingGameStore implements GameStore {
    private static final Logger log = LoggerFactory.getLogger(RoutingGameStore.class);
    private final GameStore delegate;

    @Autowired
    public RoutingGameStore(
            InMemoryGameStore inMemoryGameStore,
            ObjectProvider<FirestoreGameStore> firestoreProvider,
            @Value("${app.store:auto}") String mode
    ) {
        this(inMemoryGameStore, firestoreProvider, mode, System::getenv);
    }

    /** Package-private for tests: an injected environment lookup instead of the process environment. */
    RoutingGameStore(
            InMemoryGameStore inMemoryGameStore,
            ObjectProvider<FirestoreGameStore> firestoreProvider,
            String mode,
            Function<String, String> env
    ) {
        String normalized = mode == null || mode.isBlank() ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
        boolean cloudRun = isCloudRun(env);
        boolean firestore = switch (normalized) {
            case "auto" -> cloudRun;
            case "firestore" -> true;
            case "memory" -> false;
            default -> throw new IllegalArgumentException(
                    "Unknown app.store value '" + mode + "'; set APP_STORE to auto, firestore or memory");
        };
        String reason = !normalized.equals("auto") ? "configured" : cloudRun ? "cloud_run" : "not_cloud_run";
        this.delegate = firestore ? requireFirestore(firestoreProvider, normalized, reason) : inMemoryGameStore;
        log.info("game_store_selected store={} reason={}", firestore ? "firestore" : "memory", reason);
    }

    @Override
    public void save(Game game) {
        delegate.save(game);
    }

    @Override
    public Optional<Game> findByCode(String code) {
        return delegate.findByCode(code);
    }

    @Override
    public void deleteByCode(String code) {
        delegate.deleteByCode(code);
    }

    @Override
    public boolean existsByCode(String code) {
        return delegate.existsByCode(code);
    }

    @Override
    public Collection<Game> listAll() {
        return delegate.listAll();
    }

    @Override
    public Collection<Game> listOpenLobbies() {
        return delegate.listOpenLobbies();
    }

    @Override
    public java.util.List<LobbyProjection> listOpenLobbySummaries() {
        return delegate.listOpenLobbySummaries();
    }

    private static FirestoreGameStore requireFirestore(
            ObjectProvider<FirestoreGameStore> firestoreProvider,
            String mode,
            String reason
    ) {
        FirestoreGameStore firestore = firestoreProvider.getIfAvailable();
        if (firestore == null) {
            throw new IllegalStateException("app.store=" + mode + " selected Firestore (reason=" + reason + ") but no "
                    + "FirestoreGameStore bean is available. Refusing to fall back to in-memory storage, which loses "
                    + "every room on restart: make the FirestoreGameStore bean available, or set APP_STORE=memory to "
                    + "use in-memory storage deliberately.");
        }
        return firestore;
    }

    private static boolean isCloudRun(Function<String, String> env) {
        String kService = env.apply("K_SERVICE");
        return kService != null && !kService.isBlank();
    }
}
