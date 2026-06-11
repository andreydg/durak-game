package com.example.durakgame.service.store;

import com.example.durakgame.model.Game;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;

@Component
@Primary
public class RoutingGameStore implements GameStore {
    private final GameStore delegate;

    public RoutingGameStore(InMemoryGameStore inMemoryGameStore, ObjectProvider<FirestoreGameStore> firestoreProvider) {
        if (isCloudRun()) {
            FirestoreGameStore firestore = firestoreProvider.getIfAvailable();
            this.delegate = firestore != null ? firestore : inMemoryGameStore;
            return;
        }
        this.delegate = inMemoryGameStore;
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

    private boolean isCloudRun() {
        String kService = System.getenv("K_SERVICE");
        return kService != null && !kService.isBlank();
    }
}
