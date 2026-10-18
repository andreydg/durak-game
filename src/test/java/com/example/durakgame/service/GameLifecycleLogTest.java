package com.example.durakgame.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Suit;
import com.example.durakgame.service.autoplay.AutoPlayAction;
import com.example.durakgame.service.autoplay.AutoPlayDecisionEngine;
import com.example.durakgame.service.store.InMemoryGameStore;
import com.example.durakgame.websocket.GameWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every path that starts, finishes or interrupts a deal writes exactly one lifecycle line. */
class GameLifecycleLogTest {
    private final InMemoryGameStore store = new InMemoryGameStore();
    private final GameWebSocketHandler webSocket = new GameWebSocketHandler(new ObjectMapper());
    private ListAppender<ILoggingEvent> logs;
    private Logger lifecycleLogger;

    @BeforeEach
    void captureLogs() {
        lifecycleLogger = (Logger) LoggerFactory.getLogger(GameLifecycleLog.class);
        logs = new ListAppender<>();
        logs.start();
        lifecycleLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        lifecycleLogger.detachAppender(logs);
    }

    private GameService service(AutoPlayDecisionEngine engine) {
        return new GameService(store, engine, webSocket);
    }

    private GameService service() {
        return service((game, playerId, legalMoves) -> null);
    }

    private List<String> lines() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private String onlyLine() {
        List<String> lines = lines();
        assertEquals(1, lines.size(), () -> "expected one lifecycle line, got " + lines);
        return lines.getFirst();
    }

    @Test
    void quickPlayLogsTheStartOfItsDeal() {
        Game game = service().createQuickGame("Alice");

        String line = onlyLine();
        assertTrue(line.startsWith("game_started code=" + game.getCode() + " deal=" + GameLifecycleLog.dealId(game) + " "), line);
        assertTrue(line.contains("trigger=quick_play"), line);
        assertTrue(line.contains("players=2 humans=1 bots=1 publicRoom=false"), line);
    }

    @Test
    void aBotFillingTheTableLogsTheStart() {
        GameService service = service();
        Game lobby = service.createGame("Host");
        service.joinGame(lobby.getCode(), "Ann");
        service.joinGame(lobby.getCode(), "Bob");
        assertTrue(lines().isEmpty(), "joining a lobby that isn't full starts nothing");

        service.addBot(lobby.getCode(), lobby.getHostPlayerId(), null);

        String line = onlyLine();
        assertTrue(line.startsWith("game_started code=" + lobby.getCode()), line);
        assertTrue(line.contains("trigger=table_full"), line);
        assertTrue(line.contains("players=4 humans=3 bots=1"), line);
    }

    @Test
    void ordinaryMovesLogNothing() {
        store.save(lastBout());

        service().attack("TEST01", "h", Card.fromCode("7H"));

        assertTrue(lines().isEmpty(), () -> String.valueOf(lines()));
    }

    @Test
    void aPlayersFinalEndRoundLogsTheEnd() {
        Game game = lastBout();
        store.save(game);
        GameService service = service();

        service.attack("TEST01", "h", Card.fromCode("7H"));
        service.defend("TEST01", "g", Card.fromCode("7H"), Card.fromCode("9H"));
        service.endRound("TEST01", "h");

        String line = onlyLine();
        assertTrue(line.startsWith("game_ended code=TEST01 deal=" + GameLifecycleLog.dealId(game) + " "), line);
        assertTrue(line.contains("endedBy=player bouts=1 loser=human loserPlayerId=g"), line);
        assertTrue(line.contains("humans=2 bots=0"), line);
    }

    @Test
    void aBotsFinalEndRoundLogsTheEndToo() throws InterruptedException {
        // b (bot, attacker) has played its last card and h beat it; ending the bout ends the game.
        long now = Instant.now().toEpochMilli();
        Game game = Game.fromSnapshot(new Game.Snapshot(
                "TEST01", now, now, "h", GameStatus.IN_PROGRESS, Suit.SPADES, null, 1, 0, null, false, 0, 0L,
                List.of(player("h", false, "6C"), player("b", true)),
                List.of(), List.of(new Game.AttackSnapshot(Card.fromCode("7H"), Card.fromCode("9H"), "b")),
                Set.of(), List.of(), List.of()));
        store.save(game);
        GameService service = service((g, playerId, legalMoves) -> AutoPlayAction.endRound());

        service.resumeAutoPlayIfStalled(service.getGame("TEST01"));

        assertTrue(waitUntil(() -> service.getGame("TEST01").getStatus() == GameStatus.FINISHED, 10_000));
        String line = onlyLine();
        assertTrue(line.startsWith("game_ended code=TEST01 deal=" + GameLifecycleLog.dealId(game) + " "), line);
        assertTrue(line.contains("endedBy=bot bouts=1 loser=human loserPlayerId=h"), line);
    }

    @Test
    void aRematchStartsANewDeal() {
        Game finished = finishedGame();
        store.save(finished);

        Game rematched = service().rematch("TEST01", "h");

        String line = onlyLine();
        assertTrue(line.startsWith("game_started code=TEST01 deal=" + GameLifecycleLog.dealId(rematched) + " "), line);
        assertTrue(line.contains("trigger=rematch"), line);
        assertNotEquals(GameLifecycleLog.dealId(finished), GameLifecycleLog.dealId(rematched));
    }

    @Test
    void leavingMidDealInterruptsThatDeal() {
        Game game = lastBout();
        store.save(game);

        boolean closed = service().leaveGame("TEST01", "g");

        assertFalse(closed);
        String line = onlyLine();
        assertTrue(line.startsWith("game_interrupted code=TEST01 deal=" + GameLifecycleLog.dealId(game) + " "), line);
        assertTrue(line.contains("reason=player_left bouts=0 players=1 humans=1 bots=0"), line);
    }

    @Test
    void theLastHumanLeavingMidDealClosesTheRoom() {
        long now = Instant.now().toEpochMilli();
        Game game = inProgress(List.of(player("h", false, "7H"), player("b", true, "9H", "6C")), now);
        store.save(game);

        boolean closed = service().leaveGame("TEST01", "h");

        assertTrue(closed);
        String line = onlyLine();
        assertTrue(line.startsWith("game_interrupted code=TEST01 deal=" + GameLifecycleLog.dealId(game) + " "), line);
        assertTrue(line.contains("reason=room_closed"), line);
    }

    /** Two humans, empty talon: h attacks with its last card, g beats it and is left holding 6C. */
    private static Game lastBout() {
        return inProgress(List.of(player("h", false, "7H"), player("g", false, "9H", "6C")),
                Instant.now().toEpochMilli());
    }

    private static Game inProgress(List<Game.PlayerSnapshot> players, long at) {
        return Game.fromSnapshot(new Game.Snapshot(
                "TEST01", at, at, players.getFirst().id(), GameStatus.IN_PROGRESS, Suit.SPADES, null, 0, 1,
                null, false, 0, 0L, players, List.of(), List.of(), Set.of(), List.of(), List.of()));
    }

    private static Game finishedGame() {
        long earlier = Instant.now().minusSeconds(600).toEpochMilli();
        return Game.fromSnapshot(new Game.Snapshot(
                "TEST01", earlier, earlier, "h", GameStatus.FINISHED, Suit.SPADES, Card.fromCode("6S"), -1, -1,
                "g", false, 0, 4L, List.of(player("h", false), player("g", false, "9C")),
                List.of(), List.of(), Set.of(), List.of(), List.of()));
    }

    private static Game.PlayerSnapshot player(String id, boolean bot, String... cardCodes) {
        return new Game.PlayerSnapshot(id, id, 0L, bot, null, cards(cardCodes), id + "-secret");
    }

    private static List<Card> cards(String... codes) {
        return new ArrayList<>(Arrays.stream(codes).map(Card::fromCode).toList());
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
