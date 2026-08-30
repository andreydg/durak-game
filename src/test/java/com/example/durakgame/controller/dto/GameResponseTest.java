package com.example.durakgame.controller.dto;

import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Suit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameResponseTest {

    @Test
    void completedResultIdentitySurvivesRosterDeparture() {
        Game game = Game.fromSnapshot(new Game.Snapshot(
                "RESULT", 1L, 2L, "host", GameStatus.FINISHED,
                Suit.SPADES, Card.fromCode("6S"), -1, -1, "guest", false, 0, 9L,
                List.of(
                        new Game.PlayerSnapshot("host", "Host", 1L, false, 0, List.of(), "host-secret"),
                        new Game.PlayerSnapshot("guest", "Guest", 2L, false, 1,
                                List.of(Card.fromCode("9C")), "guest-secret")
                ),
                List.of(), List.of(), Set.of(), List.of(), List.of(), false
        ));
        game.removePlayerFromFinishedGame("guest");

        GameResponse response = GameResponse.from(game, 4, "host", Map.of());

        assertEquals("guest", response.loserPlayerId());
        assertEquals("Guest", response.loserPlayerName());
        assertEquals(1, response.loserTeam());
        assertEquals(List.of("host"), response.players().stream().map(GameResponse.PlayerSummary::id).toList());
    }
}
