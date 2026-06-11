package com.example.durakgame.model;

import com.example.durakgame.service.autoplay.AutoPlayAction;
import com.example.durakgame.service.autoplay.HeuristicAutoPlayDecisionEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class GameTest {

    @Test
    void startDealsSixCardsAndAssignsRoles() {
        Game game = new Game("CODE01", new Player("Host"));
        game.addPlayer("Guest", 4);
        game.start(game.getHostPlayerId());

        assertEquals(GameStatus.IN_PROGRESS, game.getStatus());
        assertNotNull(game.getTrumpSuit());
        assertNotNull(game.getTrumpCard());
        for (Player player : game.getPlayers()) {
            assertEquals(6, player.handSize());
        }
        assertEquals(36 - 12, game.getTalonSize());
        assertNotNull(game.getAttackerPlayerId());
        assertNotNull(game.getDefenderPlayerId());
        assertNotEquals(game.getAttackerPlayerId(), game.getDefenderPlayerId());
    }

    @Test
    void startRequiresHostAndTwoPlayers() {
        Game game = new Game("CODE02", new Player("Host"));
        assertThrows(IllegalStateException.class, () -> game.start(game.getHostPlayerId()));
        Player guest = game.addPlayer("Guest", 4);
        assertThrows(IllegalStateException.class, () -> game.start(guest.getId()));
    }

    @Test
    void podkidnoyAttackMustMatchRankOnTable() {
        Game game = inProgress(
                List.of(
                        player("a", null, "6H", "7S"),
                        player("b", null, "9C", "9D", "9H")
                ),
                Suit.SPADES, cards("8C"), 0, 1);

        game.attack("a", Card.fromCode("6H"));
        assertThrows(IllegalStateException.class, () -> game.attack("a", Card.fromCode("7S")));
        assertEquals(1, game.getTable().size());
    }

    @Test
    void defenderCannotAttack() {
        Game game = inProgress(
                List.of(
                        player("a", null, "6H"),
                        player("b", null, "6S", "9D")
                ),
                Suit.SPADES, cards("8C"), 0, 1);

        assertThrows(IllegalStateException.class, () -> game.attack("b", Card.fromCode("6S")));
    }

    @Test
    void defenseMustBeatAttackCard() {
        Game game = inProgress(
                List.of(
                        player("a", null, "8H", "6C"),
                        player("b", null, "7H", "9H", "6D", "6S")
                ),
                Suit.SPADES, cards("8C"), 0, 1);

        game.attack("a", Card.fromCode("8H"));
        // Lower same-suit card cannot beat.
        assertThrows(IllegalStateException.class,
                () -> game.defend("b", Card.fromCode("8H"), Card.fromCode("7H")));
        // Off-suit non-trump cannot beat.
        assertThrows(IllegalStateException.class,
                () -> game.defend("b", Card.fromCode("8H"), Card.fromCode("6D")));
        // Trump beats non-trump.
        game.defend("b", Card.fromCode("8H"), Card.fromCode("6S"));
        assertTrue(game.getTable().getFirst().isDefended());
    }

    @Test
    void higherSameSuitBeats() {
        Game game = inProgress(
                List.of(
                        player("a", null, "8H", "6C"),
                        player("b", null, "9H", "6D")
                ),
                Suit.SPADES, cards("8C"), 0, 1);

        game.attack("a", Card.fromCode("8H"));
        game.defend("b", Card.fromCode("8H"), Card.fromCode("9H"));
        assertTrue(game.getTable().getFirst().isDefended());
    }

    @Test
    void transferShiftsBoutToNextDefender() {
        // Table order is counterclockwise: after defender c (seat 2), the next defender is b (seat 1).
        Game game = inProgress(
                List.of(
                        player("a", null, "6H", "8C"),
                        player("b", null, "9C", "9H", "QD"),
                        player("c", null, "6S", "9D")
                ),
                Suit.SPADES, cards("8D"), 0, 2);

        game.attack("a", Card.fromCode("6H"));
        game.transfer("c", Card.fromCode("6S"));

        assertEquals("c", game.getAttackerPlayerId());
        assertEquals("b", game.getDefenderPlayerId());
        assertEquals(2, game.getTable().size());
    }

    @Test
    void transferRejectedAfterDefenseStartedOrWrongRank() {
        Game game = inProgress(
                List.of(
                        player("a", null, "6H", "7H"),
                        player("b", null, "9C", "9D", "QD"),
                        player("c", null, "6S", "7S", "9H")
                ),
                Suit.SPADES, cards("8D"), 0, 2);

        game.attack("a", Card.fromCode("6H"));
        // Rank mismatch.
        assertThrows(IllegalStateException.class, () -> game.transfer("c", Card.fromCode("7S")));
        game.defend("c", Card.fromCode("6H"), Card.fromCode("9H"));
        // After any defense, transfer window is closed.
        assertThrows(IllegalStateException.class, () -> game.transfer("c", Card.fromCode("6S")));
    }

    @Test
    void transferRejectedWhenNextDefenderHasTooFewCards() {
        Game game = inProgress(
                List.of(
                        player("a", null, "6H", "7H"),
                        player("b", null, "9C"),
                        player("c", null, "6S", "9D")
                ),
                Suit.SPADES, cards("8D"), 0, 2);

        game.attack("a", Card.fromCode("6H"));
        // b holds 1 card but would face 2 attacks.
        assertThrows(IllegalStateException.class, () -> game.transfer("c", Card.fromCode("6S")));
    }

    @Test
    void takeOpensThrowInWindowUpToLimit() {
        Game game = inProgress(
                List.of(
                        player("a", null, "6H", "6C", "6D", "8C"),
                        player("b", null, "7C", "9D")
                ),
                Suit.SPADES, cards("8D", "10C", "JC", "QC"), 0, 1);

        game.attack("a", Card.fromCode("6H"));
        game.takeCards("b");
        assertTrue(game.isTakingCardsInProgress());
        assertEquals(2, game.getTakeLimit());

        game.attack("a", Card.fromCode("6C"));
        // Limit reached: no more throw-ins.
        assertThrows(IllegalStateException.class, () -> game.attack("a", Card.fromCode("6D")));

        game.endRound("a");
        // Defender picked up both table cards plus refill is for attackers only.
        Player defender = game.getPlayers().stream().filter(p -> p.getId().equals("b")).findFirst().orElseThrow();
        assertEquals(4, defender.handSize());
        assertTrue(defender.getHand().contains(Card.fromCode("6H")));
        assertTrue(defender.getHand().contains(Card.fromCode("6C")));
        // Picked-up cards become public knowledge.
        assertEquals(List.of(Card.fromCode("6H"), Card.fromCode("6C")), game.getKnownCardsByPlayer().get("b"));
        // Taking defender loses the next attack: attacker seat skips them.
        assertEquals("a", game.getAttackerPlayerId());
    }

    @Test
    void endRoundRequiresApprovalFromAllAttackingSidePlayers() {
        Game game = inProgress(
                List.of(
                        player("a", null, "6H", "8C"),
                        player("b", null, "9C", "QD"),
                        player("c", null, "7H", "9D")
                ),
                Suit.SPADES, cards("8D", "10C"), 0, 2);

        game.attack("a", Card.fromCode("6H"));
        game.defend("c", Card.fromCode("6H"), Card.fromCode("7H"));

        game.endRound("a");
        // b has not approved yet: bout still open.
        assertEquals(1, game.getTable().size());
        game.endRound("b");
        assertEquals(0, game.getTable().size());
        // Defended cards leave play permanently.
        assertTrue(game.getDiscardedCards().containsAll(cards("6H", "7H")));
        // Roles rotate: previous defender attacks next.
        assertEquals("c", game.getAttackerPlayerId());
    }

    @Test
    void defenderCannotEndRound() {
        Game game = inProgress(
                List.of(
                        player("a", null, "6H", "8C"),
                        player("b", null, "7H", "9D")
                ),
                Suit.SPADES, cards("8D"), 0, 1);

        game.attack("a", Card.fromCode("6H"));
        game.defend("b", Card.fromCode("6H"), Card.fromCode("7H"));
        assertThrows(IllegalStateException.class, () -> game.endRound("b"));
    }

    @Test
    void gameFinishesWhenOnlyOnePlayerHoldsCards() {
        Game game = inProgress(
                List.of(
                        player("a", null, "6H"),
                        player("b", null, "7H", "8C")
                ),
                Suit.SPADES, List.of(), 0, 1);

        game.attack("a", Card.fromCode("6H"));
        game.defend("b", Card.fromCode("6H"), Card.fromCode("7H"));
        game.endRound("a");

        assertEquals(GameStatus.FINISHED, game.getStatus());
        assertEquals("b", game.getLoserPlayerId());
    }

    @Test
    void teamGameFinishesWhenOnlyOneTeamHoldsCards() {
        Game game = inProgress(
                List.of(
                        player("a", 0, "6H", "6S"),
                        player("b", 1, "QH"),
                        player("c", 0, "7C"),
                        player("d", 1)
                ),
                Suit.SPADES, List.of(), 0, 1);

        game.attack("a", Card.fromCode("6H"));
        game.defend("b", Card.fromCode("6H"), Card.fromCode("QH"));
        game.endRound("a");
        game.endRound("c");

        // Only team 0 (a and c) still holds cards: play cannot continue between teammates.
        assertEquals(GameStatus.FINISHED, game.getStatus());
        assertNotNull(game.getLoserPlayerId());
        Player loser = game.getPlayers().stream()
                .filter(p -> p.getId().equals(game.getLoserPlayerId()))
                .findFirst().orElseThrow();
        assertEquals(0, loser.getTeam());
    }

    @Test
    void advertisedLegalMovesAlwaysApply() {
        HeuristicAutoPlayDecisionEngine engine = new HeuristicAutoPlayDecisionEngine();
        for (int run = 0; run < 10; run++) {
            int playerCount = 2 + run % 3;
            Game game = new Game("RUN" + run, new Player("p0"));
            for (int i = 1; i < playerCount; i++) {
                game.addPlayer("p" + i, 4);
            }
            game.start(game.getHostPlayerId());

            int steps = 0;
            while (game.getStatus() == GameStatus.IN_PROGRESS && steps++ < 1000) {
                boolean advanced = false;
                for (Player player : game.getPlayers()) {
                    ViewerLegalMoves moves = game.computeViewerLegalMoves(player.getId());
                    assertAdvertisedMovesApply(game, player.getId(), moves);
                    AutoPlayAction action = engine.choose(game, player.getId(), moves);
                    if (action == null) {
                        continue;
                    }
                    assertDoesNotThrow(() -> apply(game, player.getId(), action),
                            "heuristic action must be legal: " + action);
                    advanced = true;
                    break;
                }
                if (!advanced) {
                    fail("game stalled while in progress (run " + run + ", step " + steps + ")");
                }
            }
            assertEquals(GameStatus.FINISHED, game.getStatus(), "game should finish (run " + run + ")");
        }
    }

    private void assertAdvertisedMovesApply(Game game, String playerId, ViewerLegalMoves moves) {
        for (String code : moves.attackableCardCodes()) {
            Game copy = Game.fromSnapshot(game.toSnapshot());
            assertDoesNotThrow(() -> copy.attack(playerId, Card.fromCode(code)),
                    "advertised attack must apply: " + code);
        }
        for (String code : moves.transferableCardCodes()) {
            Game copy = Game.fromSnapshot(game.toSnapshot());
            assertDoesNotThrow(() -> copy.transfer(playerId, Card.fromCode(code)),
                    "advertised transfer must apply: " + code);
        }
        for (Map.Entry<String, List<String>> entry : moves.defensesByAttackCard().entrySet()) {
            for (String defense : entry.getValue()) {
                Game copy = Game.fromSnapshot(game.toSnapshot());
                assertDoesNotThrow(
                        () -> copy.defend(playerId, Card.fromCode(entry.getKey()), Card.fromCode(defense)),
                        "advertised defense must apply: " + entry.getKey() + " with " + defense);
            }
        }
        if (moves.canTake()) {
            Game copy = Game.fromSnapshot(game.toSnapshot());
            assertDoesNotThrow(() -> copy.takeCards(playerId), "advertised take must apply");
        }
        if (moves.canEndRound()) {
            Game copy = Game.fromSnapshot(game.toSnapshot());
            assertDoesNotThrow(() -> copy.endRound(playerId), "advertised end round must apply");
        }
    }

    private void apply(Game game, String playerId, AutoPlayAction action) {
        switch (action.type()) {
            case ATTACK -> game.attack(playerId, Card.fromCode(action.cardCode()));
            case DEFEND -> game.defend(playerId, Card.fromCode(action.attackCardCode()), Card.fromCode(action.cardCode()));
            case TRANSFER -> game.transfer(playerId, Card.fromCode(action.cardCode()));
            case TAKE -> game.takeCards(playerId);
            case END_ROUND -> game.endRound(playerId);
        }
    }

    private static Game inProgress(
            List<Game.PlayerSnapshot> players,
            Suit trumpSuit,
            List<Card> talon,
            int attackerIndex,
            int defenderIndex
    ) {
        return Game.fromSnapshot(new Game.Snapshot(
                "TEST01",
                0L,
                players.getFirst().id(),
                GameStatus.IN_PROGRESS,
                trumpSuit,
                null,
                attackerIndex,
                defenderIndex,
                null,
                false,
                0,
                0L,
                players,
                talon,
                List.of(),
                Set.of(),
                List.of(),
                List.of()
        ));
    }

    private static Game.PlayerSnapshot player(String id, Integer team, String... cardCodes) {
        return new Game.PlayerSnapshot(id, id, 0L, false, team, cards(cardCodes));
    }

    private static List<Card> cards(String... codes) {
        return new ArrayList<>(Arrays.stream(codes).map(Card::fromCode).toList());
    }
}
