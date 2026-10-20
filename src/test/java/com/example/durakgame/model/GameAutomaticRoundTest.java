package com.example.durakgame.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameAutomaticRoundTest {
    @Test
    void finalDefenceEndsBoutWhenDefenderHasNoCardsEvenIfAttackerHasMatchingRank() {
        Game game = game(List.of(player("a", null, "6H", "6D"), player("b", null, "7H")),
                cards("8C", "9C", "10C", "JC", "QC", "KC", "AC", "8D", "9D", "10D", "JD"), 1);
        game.attack("a", card("6H"));
        long before = game.getVersion();

        game.defend("b", card("6H"), card("7H"));

        assertTrue(game.getTable().isEmpty());
        assertEquals(cards("6H", "7H"), game.getDiscardedCards());
        assertEquals(1, game.getBoutsCompleted());
        assertEquals(before + 1, game.getVersion(), "defence and automatic settlement are one mutation");
        assertEquals("b", game.getAttackerPlayerId());
        assertEquals("a", game.getDefenderPlayerId());
        assertTrue(game.getPlayers().stream().allMatch(p -> p.handSize() == 6));
        assertFalse(game.computeViewerLegalMoves("a").canEndRound());
        assertEquals(1, Game.fromSnapshot(game.toSnapshot()).getBoutsCompleted());
    }

    @Test
    void finalDefenceEndsBoutWhenNoAttackerHasAMatchingRank() {
        Game game = game(List.of(player("a", null, "6H", "KD"), player("b", null, "7H", "8C")),
                cards("9C"), 1);
        game.attack("a", card("6H"));
        game.defend("b", card("6H"), card("7H"));

        assertTrue(game.getTable().isEmpty());
        assertEquals(1, game.getBoutsCompleted());
        assertEquals("b", game.getAttackerPlayerId());
    }

    @Test
    void defenceRankCanStillBeThrownInSoManualEndRoundRemainsAvailable() {
        Game game = game(List.of(player("a", null, "6H", "7D"), player("b", null, "7H", "8C")),
                cards("9C"), 1);
        game.attack("a", card("6H"));
        game.defend("b", card("6H"), card("7H"));

        assertEquals(1, game.getTable().size());
        assertEquals(List.of("7D"), game.computeViewerLegalMoves("a").attackableCardCodes());
        assertTrue(game.computeViewerLegalMoves("a").canEndRound());
        game.endRound("a");
        assertEquals(1, game.getBoutsCompleted());
    }

    @Test
    void anotherAttackerCanKeepAThreePlayerBoutOpen() {
        Game game = game(List.of(player("a", null, "6H", "KD"), player("b", null, "7D"),
                player("c", null, "7H", "8C")), cards("9C"), 2);
        game.attack("a", card("6H"));
        game.defend("c", card("6H"), card("7H"));

        assertFalse(game.computeViewerLegalMoves("a").canAttack());
        assertTrue(game.computeViewerLegalMoves("b").canAttack());
        assertEquals(1, game.getTable().size());
        assertEquals(0, game.getBoutsCompleted());
    }

    @Test
    void defendersTeammateCannotKeepABoutOpen() {
        Game game = game(List.of(player("a", 0, "6H", "KD"), player("b", 1, "7H", "8C"),
                player("c", 0, "QD"), player("d", 1, "7D")), cards("9C"), 1);
        game.attack("a", card("6H"));
        game.defend("b", card("6H"), card("7H"));

        assertTrue(game.getTable().isEmpty());
        assertEquals(1, game.getBoutsCompleted());
    }

    @Test
    void attackingTeammatesLegalThrowInKeepsABoutOpen() {
        Game game = game(List.of(player("a", 0, "6H", "KD"), player("b", 1, "7H", "8C"),
                player("c", 0, "7D"), player("d", 1, "QD")), cards("9C"), 1);
        game.attack("a", card("6H"));
        game.defend("b", card("6H"), card("7H"));

        assertTrue(game.computeViewerLegalMoves("c").canAttack());
        assertEquals(1, game.getTable().size());
        game.endRound("a");
        assertEquals(1, game.getTable().size(), "the other attacker still chooses whether to continue");
        game.endRound("c");
        assertTrue(game.getTable().isEmpty());
    }

    @Test
    void neverEndsWhileAnAttackStillNeedsDefending() {
        Game game = game(List.of(player("a", null, "6H", "6D", "KD"),
                player("b", null, "7H", "7D", "8C")), cards("9C"), 1);
        game.attack("a", card("6H"));
        game.attack("a", card("6D"));
        game.defend("b", card("6H"), card("7H"));
        assertEquals(2, game.getTable().size());
        assertEquals(0, game.getBoutsCompleted());
        game.defend("b", card("6D"), card("7D"));
        assertTrue(game.getTable().isEmpty());
        assertEquals(1, game.getBoutsCompleted());
    }

    @Test
    void automaticSettlementFinishesTheDealWithTheCorrectLoser() {
        Game game = game(List.of(player("a", null, "6H"), player("b", null, "7H", "8C")), List.of(), 1);
        game.attack("a", card("6H"));
        game.defend("b", card("6H"), card("7H"));

        assertEquals(GameStatus.FINISHED, game.getStatus());
        assertEquals("b", game.getLoserPlayerId());
        assertEquals(1, game.getBoutsCompleted());
        assertTrue(game.getTable().isEmpty());
    }

    @Test
    void automaticSettlementCanFinishWithNoLoser() {
        Game game = game(List.of(player("a", null, "6H"), player("b", null, "7H")), List.of(), 1);
        game.attack("a", card("6H"));
        game.defend("b", card("6H"), card("7H"));

        assertEquals(GameStatus.FINISHED, game.getStatus());
        assertNull(game.getLoserPlayerId());
        assertEquals(1, game.getBoutsCompleted());
    }

    private static Game game(List<Game.PlayerSnapshot> players, List<Card> talon, int defender) {
        return Game.fromSnapshot(new Game.Snapshot("AUTO23", 0L, 0L, "a", GameStatus.IN_PROGRESS,
                Suit.SPADES, null, 0, defender, null, false, 0, 0L, players, talon,
                List.of(), Set.of(), List.of(), List.of()));
    }

    private static Game.PlayerSnapshot player(String id, Integer team, String... cards) {
        return new Game.PlayerSnapshot(id, id, 0L, false, team, cards(cards), "");
    }

    private static Card card(String code) { return Card.fromCode(code); }

    private static List<Card> cards(String... codes) {
        return Arrays.stream(codes).map(Card::fromCode).toList();
    }
}
