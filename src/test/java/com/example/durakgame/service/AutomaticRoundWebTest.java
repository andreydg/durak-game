package com.example.durakgame.service;

import com.example.durakgame.controller.ApiExceptionHandler;
import com.example.durakgame.controller.GameController;
import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Suit;
import com.example.durakgame.service.store.InMemoryGameStore;
import com.example.durakgame.websocket.GameWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The response, persisted state, and websocket invalidation all describe the same settled bout. */
class AutomaticRoundWebTest {
    private final InMemoryGameStore store = new InMemoryGameStore();
    private final GameWebSocketHandler sockets = mock(GameWebSocketHandler.class);
    private final GameService service = new GameService(store, (g, id, moves) -> null, sockets);

    private MockMvc mvc() {
        when(sockets.botThinkingForGame(anyString())).thenReturn(Map.of());
        return MockMvcBuilders.standaloneSetup(new GameController(service, sockets))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void lastDefenceReturnsAndBroadcastsTheNextBoutWithoutAnEndRoundRequest() throws Exception {
        seed(false);
        MockMvc mvc = mvc();

        mvc.perform(post("/api/games/AUTO23/defend").header("X-Durak-Token", "b-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\":\"b\",\"attackCard\":\"6H\",\"defenseCard\":\"7H\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table").isEmpty())
                .andExpect(jsonPath("$.version").value(41))
                .andExpect(jsonPath("$.attackerPlayerId").value("b"))
                .andExpect(jsonPath("$.players[1].handSize").value(6))
                .andExpect(jsonPath("$.legalMoves.canEndRound").value(false));

        verify(sockets).broadcastGameUpdated("AUTO23", 41L);
        assertEquals(1, store.findByCode("AUTO23").orElseThrow().getBoutsCompleted());
        mvc.perform(get("/api/games/AUTO23").param("playerId", "a").header("X-Durak-Token", "a-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table").isEmpty())
                .andExpect(jsonPath("$.version").value(41));
        // A duplicate defence must not finish a second bout or disturb the next one.
        mvc.perform(post("/api/games/AUTO23/defend").header("X-Durak-Token", "b-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\":\"b\",\"attackCard\":\"6H\",\"defenseCard\":\"7H\"}"))
                .andExpect(status().isConflict());
        assertEquals(41L, store.findByCode("AUTO23").orElseThrow().getVersion());
    }

    @Test
    void lastDefenceReturnsTheFinalResultWithoutAnEndRoundRequest() throws Exception {
        seed(true);
        mvc().perform(post("/api/games/AUTO23/defend").header("X-Durak-Token", "b-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\":\"b\",\"attackCard\":\"6H\",\"defenseCard\":\"7H\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINISHED"))
                .andExpect(jsonPath("$.table").isEmpty())
                .andExpect(jsonPath("$.loserPlayerId").value("b"))
                .andExpect(jsonPath("$.legalMoves.canEndRound").value(false));
        assertEquals(GameStatus.FINISHED, store.findByCode("AUTO23").orElseThrow().getStatus());
    }

    private void seed(boolean lastBout) {
        long now = Instant.now().toEpochMilli();
        Game game = Game.fromSnapshot(new Game.Snapshot("AUTO23", now, now, "a", GameStatus.IN_PROGRESS,
                Suit.SPADES, null, 0, 1, null, false, 0, 40L,
                List.of(new Game.PlayerSnapshot("a", "Alice", now, false, null,
                                lastBout ? List.of() : cards("6D", "7C", "8C", "9C", "10C"), "a-secret"),
                        new Game.PlayerSnapshot("b", "Bob", now, false, null,
                                lastBout ? cards("7H", "8D") : cards("7H"), "b-secret")),
                lastBout ? List.of() : cards("JC", "QC", "KC", "AC", "8D", "9D", "10D"),
                List.of(new Game.AttackSnapshot(Card.fromCode("6H"), null, "a")), Set.of(), List.of(), List.of()));
        game.markNotPersisted();
        store.save(game);
    }

    private static List<Card> cards(String... codes) {
        return Arrays.stream(codes).map(Card::fromCode).toList();
    }
}
