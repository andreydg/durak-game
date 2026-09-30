package com.example.durakgame.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rejected player actions must leave the game untouched. Found by {@link DurakRulesFuzzTest}'s
 * probe mode: several actions used to change state before validating, destroying cards or wiping
 * other players' end-of-bout approvals when the request was then refused.
 */
class GameActionValidationTest {

    @Test
    void rejectedDefenceOfAnAlreadyBeatenAttackKeepsTheCard() {
        Game g = inProgress(List.of(
                        player("a", null, "6H", "8C"),
                        player("b", null, "7H", "9H", "KD")),
                Suit.SPADES, cards("10S"), 0, 1);
        g.attack("a", c("6H"));
        g.defend("b", c("6H"), c("7H"));

        // A double-submitted drag: a second card onto the attack that was just beaten.
        assertRejectedWithoutChanges(g, () -> g.defend("b", c("6H"), c("9H")));
        assertTrue(hand(g, "b").contains(c("9H")));
    }

    @Test
    void rejectedDefenceAgainstACardNotOnTheTableKeepsTheCard() {
        Game g = inProgress(List.of(
                        player("a", null, "6H", "8C"),
                        player("b", null, "9H", "KD")),
                Suit.SPADES, cards("10S"), 0, 1);
        g.attack("a", c("6H"));

        assertRejectedWithoutChanges(g, () -> g.defend("b", c("7C"), c("9H")));
    }

    @Test
    void defenderCannotTransferAfterChoosingToTake() {
        Game g = inProgress(List.of(
                        player("a", null, "6H", "8C", "9C"),
                        player("b", null, "6S", "7D", "KD")),
                Suit.CLUBS, cards("10C"), 0, 1);
        g.attack("a", c("6H"));
        g.takeCards("b");

        assertFalse(g.computeViewerLegalMoves("b").canTransfer());
        assertRejectedWithoutChanges(g, () -> g.transfer("b", c("6S")));
    }

    @Test
    void defenderCannotEscapeATakeByTransferringInAThreePlayerGame() {
        // a attacks c; b (next defender) holds only two cards.
        Game g = inProgress(List.of(
                        player("a", null, "6H", "6D", "6C", "8C"),
                        player("b", null, "9C", "9D"),
                        player("c", null, "6S", "7S", "8S", "9S", "10S", "JS")),
                Suit.HEARTS, cards("AH"), 0, 2);
        g.attack("a", c("6H"));
        g.takeCards("c");

        assertRejectedWithoutChanges(g, () -> g.transfer("c", c("6S")));
        assertEquals("c", g.getDefenderPlayerId());
    }

    @Test
    void defenderCannotDefendAfterChoosingToTake() {
        Game g = inProgress(List.of(
                        player("a", null, "6H", "7C"),
                        player("b", null, "7H", "KD")),
                Suit.SPADES, cards("10S"), 0, 1);
        g.attack("a", c("6H"));
        g.takeCards("b");

        assertFalse(g.computeViewerLegalMoves("b").canDefend());
        // Accepting it would add the 7 to the table ranks and open new throw-ins against the taker.
        assertRejectedWithoutChanges(g, () -> g.defend("b", c("6H"), c("7H")));
    }

    @Test
    void rejectedThrowInKeepsOtherPlayersEndOfBoutApprovals() {
        Game g = inProgress(List.of(
                        player("a", null, "6H", "8C"),
                        player("b", null, "9C", "QD", "7D"),
                        player("c", null, "7H", "9D")),
                Suit.SPADES, cards("8D", "10S"), 0, 2);
        g.attack("a", c("6H"));
        g.defend("c", c("6H"), c("7H"));
        g.endRound("a");

        assertRejectedWithoutChanges(g, () -> g.attack("b", c("QD")));
        g.endRound("b");
        assertTrue(g.getTable().isEmpty(), "a's approval must survive b's rejected throw-in");
    }

    @Test
    void rejectedAttacksDoNotReorderTheHand() {
        Game g = inProgress(List.of(
                        player("a", null, "6H", "QD", "8C"),
                        player("b", null, "7H", "9D", "KD")),
                Suit.SPADES, cards("10S"), 0, 1);
        g.attack("a", c("6H"));

        assertRejectedWithoutChanges(g, () -> g.attack("a", c("QD")));
        assertRejectedWithoutChanges(g, () -> g.attack("a", c("AS")));
        assertEquals(List.of(c("QD"), c("8C")), hand(g, "a"));
    }

    @Test
    void startingWithMorePlayersThanTheDeckSupportsDealsNothing() {
        Game g = new Game("SIXPLR", new Player("P0"));
        for (int i = 1; i < 6; i++) {
            g.addPlayer("P" + i, 10);
        }

        assertThrows(IllegalStateException.class, () -> g.start(g.getHostPlayerId()));
        assertEquals(GameStatus.LOBBY, g.getStatus());
        assertEquals(0, g.getPlayers().stream().mapToInt(Player::handSize).sum());
    }

    private static void assertRejectedWithoutChanges(Game g, Executable action) {
        Game.Snapshot before = g.toSnapshot();
        int cardsBefore = DurakRulesFuzzTest.countCards(before);
        assertThrows(IllegalStateException.class, action);
        assertEquals(before, g.toSnapshot(), "a rejected action must not change the game");
        assertEquals(cardsBefore, DurakRulesFuzzTest.countCards(g.toSnapshot()));
    }

    private static Game inProgress(List<Game.PlayerSnapshot> players, Suit trump, List<Card> talon,
                                   int attacker, int defender) {
        return Game.fromSnapshot(new Game.Snapshot("VALID1", 0L, 0L, players.getFirst().id(),
                GameStatus.IN_PROGRESS, trump, talon.getLast(), attacker, defender, null, false, 0, 0L,
                players, talon, List.of(), Set.of(), List.of(), List.of()));
    }

    private static Game.PlayerSnapshot player(String id, Integer team, String... codes) {
        return new Game.PlayerSnapshot(id, id, 0L, false, team, cards(codes), "");
    }

    private static List<Card> cards(String... codes) {
        return new ArrayList<>(Arrays.stream(codes).map(Card::fromCode).toList());
    }

    private static Card c(String code) {
        return Card.fromCode(code);
    }

    private static List<Card> hand(Game g, String id) {
        return g.getPlayers().stream().filter(p -> p.getId().equals(id)).findFirst().orElseThrow().getHand();
    }
}
