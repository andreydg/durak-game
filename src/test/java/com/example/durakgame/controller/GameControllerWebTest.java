package com.example.durakgame.controller;

import com.example.durakgame.controller.dto.LobbyGameSummary;
import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.Player;
import com.example.durakgame.service.GameService;
import com.example.durakgame.service.UnauthorizedActionException;
import com.example.durakgame.service.store.StaleGameWriteException;
import com.example.durakgame.websocket.GameWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests using standalone {@link MockMvc} (no Spring context) over the controller,
 * bean validation, and {@link ApiExceptionHandler} status mapping.
 */
class GameControllerWebTest {

    private final GameService gameService = mock(GameService.class);
    private final GameWebSocketHandler webSocketHandler = mock(GameWebSocketHandler.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new GameController(gameService, webSocketHandler), new LobbyController(gameService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        when(gameService.getMaxPlayers()).thenReturn(4);
        when(webSocketHandler.botThinkingForGame(anyString())).thenReturn(Map.of());
    }

    @Test
    void createGameReturnsCodeAndHostId() throws Exception {
        Game game = new Game("ABC123", new Player("Alice"));
        when(gameService.createGame("Alice", true)).thenReturn(game);

        mockMvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hostName\":\"Alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.game.code").value("ABC123"))
                .andExpect(jsonPath("$.game.publicRoom").value(true))
                .andExpect(jsonPath("$.hostPlayerId").value(game.getPlayers().getFirst().getId()));
    }

    @Test
    void createGameCanMakeInviteOnlyRoom() throws Exception {
        Game game = new Game("PVT123", new Player("Alice"), false);
        when(gameService.createGame("Alice", false)).thenReturn(game);

        mockMvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hostName\":\"Alice\",\"publicRoom\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.game.publicRoom").value(false));

        verify(gameService).createGame("Alice", false);
    }

    @Test
    void quickPlayReturnsPrivateGameSession() throws Exception {
        Game game = new Game("BOT123", new Player("Alice"), false);
        when(gameService.createQuickGame("Alice")).thenReturn(game);

        mockMvc.perform(post("/api/games/quick-play")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hostName\":\"Alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.game.code").value("BOT123"))
                .andExpect(jsonPath("$.game.publicRoom").value(false))
                .andExpect(jsonPath("$.playerToken").isNotEmpty());
    }

    @Test
    void rematchRequiresTokenAndReturnsFreshState() throws Exception {
        Game game = new Game("ABC123", new Player("Alice"));
        String hostId = game.getHostPlayerId();
        when(gameService.rematch("ABC123", hostId)).thenReturn(game);

        mockMvc.perform(post("/api/games/ABC123/rematch")
                        .header("X-Durak-Token", "good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\":\"" + hostId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ABC123"));

        verify(gameService).requireAuthorized("ABC123", hostId, "good-token");
        verify(gameService).rematch("ABC123", hostId);
        verify(webSocketHandler).broadcastGameUpdated("ABC123", game.getVersion());
    }

    @Test
    void createGameRejectsOverlongNameWith400() throws Exception {
        mockMvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hostName\":\"" + "x".repeat(25) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getUnknownGameReturns404() throws Exception {
        when(gameService.getGame("ZZZ999")).thenThrow(new NoSuchElementException("Game not found"));

        mockMvc.perform(get("/api/games/ZZZ999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Game not found"));
    }

    @Test
    void illegalActionReturns409Conflict() throws Exception {
        when(gameService.attack(eq("ABC123"), eq("p1"), any(Card.class)))
                .thenThrow(new IllegalStateException("Defender cannot attack"));

        mockMvc.perform(post("/api/games/ABC123/attack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\":\"p1\",\"card\":\"9H\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Defender cannot attack"));
    }

    @Test
    void staleWriteReturns409WithFriendlyMessage() throws Exception {
        when(gameService.attack(eq("ABC123"), eq("p1"), any(Card.class)))
                .thenThrow(new StaleGameWriteException("ABC123", 1, 2));

        mockMvc.perform(post("/api/games/ABC123/attack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\":\"p1\",\"card\":\"9H\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Game state changed. Please retry your move."));
    }

    @Test
    void attackWithBlankPlayerIdReturns400() throws Exception {
        mockMvc.perform(post("/api/games/ABC123/attack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\":\"\",\"card\":\"9H\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void actionWithBadTokenReturns403() throws Exception {
        doThrow(new UnauthorizedActionException())
                .when(gameService).requireAuthorized("ABC123", "p1", "bad-token");

        mockMvc.perform(post("/api/games/ABC123/attack")
                        .header("X-Durak-Token", "bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\":\"p1\",\"card\":\"9H\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void revealsHandOnlyToAuthorizedViewer() throws Exception {
        Game game = new Game("ABC123", new Player("Alice"));
        game.addPlayer("Bob", 4);
        game.start(game.getHostPlayerId());
        String viewerId = game.getPlayers().getFirst().getId();
        when(gameService.getGame("ABC123")).thenReturn(game);

        // Unauthorized viewer (default isAuthorized=false): hand is hidden.
        mockMvc.perform(get("/api/games/ABC123").param("viewerPlayerId", viewerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players[0].hand", org.hamcrest.Matchers.hasSize(0)));

        // Authorized viewer: their six cards are revealed.
        when(gameService.isAuthorized(game, viewerId, "good-token")).thenReturn(true);
        mockMvc.perform(get("/api/games/ABC123")
                        .param("viewerPlayerId", viewerId)
                        .header("X-Durak-Token", "good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players[0].hand", org.hamcrest.Matchers.hasSize(6)));
    }

    @Test
    void listLobbiesReturnsSummaries() throws Exception {
        when(gameService.listOpenLobbies()).thenReturn(List.of(
                new LobbyGameSummary(
                        "ABC123", "Alice", List.of("Alice"), 1, 4,
                        Instant.parse("2026-08-28T12:00:00Z"),
                        Instant.parse("2026-08-28T12:30:00Z")
                )));

        mockMvc.perform(get("/api/lobbies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("ABC123"))
                .andExpect(jsonPath("$[0].hostName").value("Alice"));
    }
}
