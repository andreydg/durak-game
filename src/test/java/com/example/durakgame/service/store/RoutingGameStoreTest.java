package com.example.durakgame.service.store;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RoutingGameStoreTest {
    private static final Function<String, String> LOCAL = name -> null;
    private static final Function<String, String> CLOUD_RUN = Map.of("K_SERVICE", "durak-game")::get;

    private final InMemoryGameStore memory = mock(InMemoryGameStore.class);
    private final FirestoreGameStore firestore = mock(FirestoreGameStore.class);
    private final ObjectProvider<FirestoreGameStore> firestoreBean = mock();
    // Unstubbed, getIfAvailable() answers null, as Spring does when the bean definition is missing.
    private final ObjectProvider<FirestoreGameStore> noFirestoreBean = mock();
    private final Game game = new Game("ROUTE1", new Player("Host"));

    @BeforeEach
    void setUp() {
        when(firestoreBean.getIfAvailable()).thenReturn(firestore);
    }

    @Test
    void autoUsesMemoryOffCloudRun() {
        new RoutingGameStore(memory, firestoreBean, "auto", LOCAL).save(game);

        verify(memory).save(game);
        verifyNoInteractions(firestoreBean); // the lazy Firestore client is never built
    }

    @Test
    void autoUsesFirestoreOnCloudRun() {
        new RoutingGameStore(memory, firestoreBean, "auto", CLOUD_RUN).save(game);

        verify(firestore).save(game);
        verifyNoInteractions(memory);
    }

    @Test
    void autoOnCloudRunFailsInsteadOfFallingBackToMemory() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new RoutingGameStore(memory, noFirestoreBean, "auto", CLOUD_RUN));

        assertTrue(ex.getMessage().contains("app.store=auto"), ex.getMessage());
        assertTrue(ex.getMessage().contains("APP_STORE=memory"), ex.getMessage());
    }

    @Test
    void explicitFirestoreFailsWithoutFirestoreBean() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new RoutingGameStore(memory, noFirestoreBean, "firestore", LOCAL));

        assertTrue(ex.getMessage().contains("app.store=firestore"), ex.getMessage());
    }

    @Test
    void explicitMemoryOptsOutOnCloudRun() {
        new RoutingGameStore(memory, firestoreBean, "memory", CLOUD_RUN).save(game);

        verify(memory).save(game);
        verifyNoInteractions(firestoreBean);
    }

    @Test
    void explicitFirestoreWorksOffCloudRun() {
        new RoutingGameStore(memory, firestoreBean, "firestore", LOCAL).save(game);

        verify(firestore).save(game);
        verifyNoInteractions(memory);
    }

    @Test
    void unknownValueFailsStartup() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new RoutingGameStore(memory, firestoreBean, "firestor", LOCAL));

        assertTrue(ex.getMessage().contains("'firestor'"), ex.getMessage());
    }

    @Test
    void blankValueMeansAuto() {
        new RoutingGameStore(memory, firestoreBean, " ", CLOUD_RUN).save(game);

        verify(firestore).save(game);
    }

    @Test
    void valuesIgnoreCaseAndSurroundingSpaces() {
        new RoutingGameStore(memory, firestoreBean, " Memory ", CLOUD_RUN).save(game);

        verify(memory).save(game);
    }
}
