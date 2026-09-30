package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Player;
import com.example.durakgame.model.Rank;
import com.example.durakgame.model.Suit;
import com.example.durakgame.model.ViewerLegalMoves;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class HeuristicAutoPlayDecisionEngineTest {
    private final HeuristicAutoPlayDecisionEngine engine = new HeuristicAutoPlayDecisionEngine();
    private final Game game = gameWithTrump(Suit.SPADES);

    /* Talon states as a seated human sees them (the exact count is never used). */
    private static final List<Card> FULL_TALON = cards("6D", "7D", "JC", "QC", "KC", "10H", "JH", "AS");
    private static final List<Card> LAST_TRUMP_ONLY = cards("AS");
    private static final List<Card> EMPTY_TALON = List.of();

    @Test
    void attacksWithLowestNonTrumpByRankValue() {
        // Lexicographically "10D" < "6S" < "7C", but real value ordering must win:
        // 7C is the cheapest non-trump, 6S is trump and saved for later.
        ViewerLegalMoves moves = new ViewerLegalMoves(
                false, true, false, false, false, false,
                List.of("10D", "6S", "AD", "7C"), List.of(), Map.of());

        AutoPlayAction action = engine.choose(game, "p", moves);
        assertNotNull(action);
        assertEquals(AutoPlayAction.Type.ATTACK, action.type());
        assertEquals("7C", action.cardCode());
    }

    @Test
    void defendsMostConstrainedAttackWithCheapestCard() {
        ViewerLegalMoves moves = new ViewerLegalMoves(
                false, false, true, false, true, false,
                List.of(), List.of(),
                Map.of(
                        "6H", List.of("8H", "KH"),
                        "6C", List.of("8C")
                ));

        AutoPlayAction action = engine.choose(game, "p", moves);
        assertNotNull(action);
        assertEquals(AutoPlayAction.Type.DEFEND, action.type());
        assertEquals("6C", action.attackCardCode());
        assertEquals("8C", action.cardCode());
    }

    @Test
    void prefersNonTrumpDefenseOverLowerTrump() {
        ViewerLegalMoves moves = new ViewerLegalMoves(
                false, false, true, false, true, false,
                List.of(), List.of(),
                Map.of("10H", List.of("6S", "KH")));

        AutoPlayAction action = engine.choose(game, "p", moves);
        assertNotNull(action);
        assertEquals("KH", action.cardCode());
    }

    @Test
    void transfersCheapestNonTrump() {
        ViewerLegalMoves moves = new ViewerLegalMoves(
                false, false, false, true, true, false,
                List.of(), List.of("6S", "6D"), Map.of());

        AutoPlayAction action = engine.choose(game, "p", moves);
        assertNotNull(action);
        assertEquals(AutoPlayAction.Type.TRANSFER, action.type());
        assertEquals("6D", action.cardCode());
    }

    @Test
    void takesWhenNothingElseIsPossible() {
        ViewerLegalMoves moves = new ViewerLegalMoves(
                false, false, false, false, true, false,
                List.of(), List.of(), Map.of());
        AutoPlayAction action = engine.choose(game, "p", moves);
        assertNotNull(action);
        assertEquals(AutoPlayAction.Type.TAKE, action.type());
    }

    @Test
    void endsRoundWhenOnlyEndRoundIsLegal() {
        ViewerLegalMoves moves = new ViewerLegalMoves(
                false, false, false, false, false, true,
                List.of(), List.of(), Map.of());
        AutoPlayAction action = engine.choose(game, "p", moves);
        assertNotNull(action);
        assertEquals(AutoPlayAction.Type.END_ROUND, action.type());
    }

    /* ------------------------------------------------------------------ passing */

    @Test
    void passesInsteadOfThrowingInATrumpWhileTheTalonHasCards() {
        Game state = new State()
                .player("bot", "7S", "KC", "10D")
                .player("human", "QH", "8C", "9D")
                .talon(FULL_TALON)
                .defended("7H", "9H", "bot")
                .build();

        assertAction(state, "bot", AutoPlayAction.endRound());
    }

    @Test
    void passesInsteadOfThrowingInAnAceWhileTheTalonHasCards() {
        Game state = new State()
                .player("bot", "AC", "6H", "10D")
                .player("human", "QH", "8C", "9D")
                .talon(FULL_TALON)
                .defended("8D", "AD", "bot")
                .build();

        assertAction(state, "bot", AutoPlayAction.endRound());
    }

    @Test
    void stillThrowsInLowNonTrumpsToKeepPressure() {
        Game state = new State()
                .player("bot", "9S", "7C", "KD")
                .player("human", "QH", "8C", "9D")
                .talon(FULL_TALON)
                .defended("7H", "9H", "bot")
                .build();

        // 7C is junk worth shedding; the trump 9S matches too but is kept.
        assertAction(state, "bot", AutoPlayAction.attack("7C"));
    }

    @Test
    void neverGiftsTrumpsOrAcesToADefenderWhoIsTaking() {
        Game state = new State()
                .player("bot", "8S", "AH", "10D")
                .player("human", "QH", "7C", "9D", "JC")
                .talon(FULL_TALON)
                .undefended("8C", "bot")
                .undefended("AD", "bot")
                .taking(6)
                .build();

        assertAction(state, "bot", AutoPlayAction.endRound());
    }

    @Test
    void dumpsLowNonTrumpsIntoATakingDefendersHand() {
        Game state = new State()
                .player("bot", "8D", "KH", "10S")
                .player("human", "QH", "7C", "9D", "JC")
                .talon(FULL_TALON)
                .undefended("8C", "bot")
                .taking(6)
                .build();

        assertAction(state, "bot", AutoPlayAction.attack("8D"));
    }

    @Test
    void waitsInsteadOfThrowingInAfterAlreadyPassing() {
        // The bot already approved ending the bout; the only legal move left is an unwanted trump.
        Game state = new State()
                .player("bot", "7S", "KC")
                .player("human", "QH", "8C", "9D")
                .player("third", "10C", "JD")
                .attacker(0).defender(2)
                .talon(FULL_TALON)
                .defended("7H", "9H", "bot")
                .approvals("bot")
                .build();
        ViewerLegalMoves moves = state.computeViewerLegalMoves("bot");
        assertTrue(moves.canAttack());

        assertEquals(null, engine.choose(state, "bot", moves));
    }

    /* ------------------------------------------------------------------ transfer vs defend vs take */

    @Test
    void transfersWithJunkRatherThanDefending() {
        Game state = new State()
                .player("human", "6H", "QC", "KC")
                .player("bot", "8C", "9D", "KH", "AH")
                .attacker(0).defender(1)
                .talon(FULL_TALON)
                .undefended("8D", "human")
                .build();

        assertAction(state, "bot", AutoPlayAction.transfer("8C"));
    }

    @Test
    void transfersWithATrumpOfTheAttackRankRatherThanSpendingItOnDefence() {
        Game state = new State()
                .player("human", "6H", "QC", "KC")
                .player("bot", "KS", "7S", "6D", "9D")
                .attacker(0).defender(1)
                .talon(LAST_TRUMP_ONLY)
                .undefended("7C", "human")
                .build();

        // Either way 7S leaves the hand, but transferring also makes the attacker beat a trump.
        assertAction(state, "bot", AutoPlayAction.transfer("7S"));
    }

    @Test
    void defendsCheaplyRatherThanTransferringWithATrump() {
        Game state = new State()
                .player("human", "6H", "QC", "KC")
                .player("bot", "8S", "10D", "KH", "AH")
                .attacker(0).defender(1)
                .talon(FULL_TALON)
                .undefended("8D", "human")
                .build();

        assertAction(state, "bot", AutoPlayAction.defend("8D", "10D"));
    }

    @Test
    void takesALowAttackEarlyInsteadOfSpendingAHighTrump() {
        Game state = new State()
                .player("human", "6H", "QC", "KC")
                .player("bot", "KS", "6D", "9D", "JH")
                .attacker(0).defender(1)
                .talon(FULL_TALON)
                .undefended("7C", "human")
                .build();

        assertAction(state, "bot", AutoPlayAction.take());
    }

    @Test
    void defendsWithThatTrumpOnceTheTalonIsGone() {
        Game state = new State()
                .player("human", "6H", "QC", "KC")
                .player("bot", "KS", "6D", "9D", "JH")
                .attacker(0).defender(1)
                .talon(EMPTY_TALON)
                .undefended("7C", "human")
                .build();

        assertAction(state, "bot", AutoPlayAction.defend("7C", "KS"));
    }

    @Test
    void spendsTheLowestTrumpWhenTheTalonIsDownToTheTrumpCard() {
        Game state = new State()
                .player("human", "6H", "QC", "KC")
                .player("bot", "KS", "8S", "6D", "9D")
                .attacker(0).defender(1)
                .talon(LAST_TRUMP_ONLY)
                .undefended("7C", "human")
                .build();

        // Early on this attack would be taken; with only the trump card left, the cheap trump is spent.
        assertAction(state, "bot", AutoPlayAction.defend("7C", "8S"));
    }

    @Test
    void plansTheCheapestCompleteDefenceAndStartsWithTheMostConstrainedAttack() {
        Game state = new State()
                .player("human", "6D", "QC", "KC")
                .player("bot", "8H", "KH", "8C", "7S", "AD")
                .attacker(0).defender(1)
                .talon(FULL_TALON)
                .undefended("6H", "human")
                .undefended("6C", "human")
                .build();

        // 6C can only be beaten by 8C or the trump; 6H has three options. Neither needs a trump.
        assertAction(state, "bot", AutoPlayAction.defend("6C", "8C"));
    }

    @Test
    void takesInsteadOfAPartialDefenceWhenOneAttackCannotBeBeaten() {
        Game state = new State()
                .player("human", "6D", "QC", "KC")
                .player("bot", "8H", "KH", "9C", "7D")
                .attacker(0).defender(1)
                .talon(FULL_TALON)
                .undefended("6H", "human")
                .undefended("AD", "human")
                .build();

        assertAction(state, "bot", AutoPlayAction.take());
    }

    /* ------------------------------------------------------------------ endgame */

    @Test
    void throwsInAnyCardIncludingTrumpsWhenThatEmptiesTheHand() {
        Game state = new State()
                .player("bot", "9S")
                .player("human", "QH", "8C", "9D")
                .talon(EMPTY_TALON)
                .defended("9H", "JH", "bot")
                .build();

        assertAction(state, "bot", AutoPlayAction.attack("9S"));
    }

    @Test
    void leadsACardNobodyCanBeatOnceTheTalonIsEmpty() {
        // Every spade (trump) has left the game and AC is the top club: nobody can beat it.
        Game state = new State()
                .player("bot", "AC", "7D")
                .player("human", "QH", "8C", "9D")
                .talon(EMPTY_TALON)
                .discarded("6S", "7S", "8S", "9S", "10S", "JS", "QS", "KS", "AS")
                .build();

        assertAction(state, "bot", AutoPlayAction.attack("AC"));
    }

    @Test
    void leadsTheLowestCardWhileNothingIsProvablyUnbeatable() {
        Game state = new State()
                .player("bot", "AC", "7D")
                .player("human", "QH", "8C", "9D")
                .talon(EMPTY_TALON)
                .build();

        assertAction(state, "bot", AutoPlayAction.attack("7D"));
    }

    @Test
    void throwsInTrumpsForPressureOnceTheTalonIsEmpty() {
        // Same table as passesInsteadOfThrowingInATrumpWhileTheTalonHasCards, but nothing refills now.
        Game state = new State()
                .player("bot", "7S", "KC", "10D")
                .player("human", "QH", "8C", "9D")
                .talon(EMPTY_TALON)
                .defended("7H", "9H", "bot")
                .build();

        assertAction(state, "bot", AutoPlayAction.attack("7S"));
    }

    @Test
    void throwsInHigherNonTrumpsAsTheTalonRunsOut() {
        Game opening = new State()
                .player("bot", "KC", "6H", "10D")
                .player("human", "QH", "8C", "9D")
                .talon(FULL_TALON)
                .defended("8D", "KD", "bot")
                .build();
        assertAction(opening, "bot", AutoPlayAction.endRound());

        Game lastTrumpOnly = new State()
                .player("bot", "KC", "6H", "10D")
                .player("human", "QH", "8C", "9D")
                .talon(LAST_TRUMP_ONLY)
                .defended("8D", "KD", "bot")
                .build();
        assertAction(lastTrumpOnly, "bot", AutoPlayAction.attack("KC"));
    }

    /* ------------------------------------------------------------------ information discipline */

    @Test
    void decisionsIgnoreHiddenHandsAndTheExactTalonCount() {
        Game first = new State()
                .player("human", "6H", "QC", "KC")
                .player("bot", "KS", "6D", "9D", "JH")
                .attacker(0).defender(1)
                .talon(FULL_TALON)
                .undefended("7C", "human")
                .build();
        Game second = new State()
                .player("human", "AS", "10H", "8H")
                .player("bot", "KS", "6D", "9D", "JH")
                .attacker(0).defender(1)
                .talon(cards("QC", "KC", "AS"))
                .undefended("7C", "human")
                .build();

        AutoPlayAction firstAction = engine.choose(first, "bot", first.computeViewerLegalMoves("bot"));
        AutoPlayAction secondAction = engine.choose(second, "bot", second.computeViewerLegalMoves("bot"));

        assertEquals(firstAction, secondAction);
        assertEquals(firstAction, engine.choose(first, "bot", first.computeViewerLegalMoves("bot")),
                "the heuristic must be deterministic");
    }

    @Test
    void neverStallsAndOnlyPlaysLegalMovesAcrossManyGames() {
        for (int seed = 0; seed < 150; seed++) {
            int playerCount = 2 + seed % 3;
            Game state = dealSeeded(seed, playerCount);
            int steps = 0;
            while (state.getStatus() == GameStatus.IN_PROGRESS) {
                if (++steps > 2_000) {
                    fail("game did not finish (seed " + seed + ")");
                }
                boolean acted = false;
                for (Player player : state.getPlayers()) {
                    ViewerLegalMoves moves = state.computeViewerLegalMoves(player.getId());
                    AutoPlayAction action = engine.choose(state, player.getId(), moves);
                    if (action == null) {
                        continue;
                    }
                    assertTrue(AutoPlayLegality.isLegal(action, moves),
                            "illegal heuristic action " + action + " (seed " + seed + ")");
                    apply(state, player.getId(), action);
                    acted = true;
                    break;
                }
                if (!acted) {
                    fail("every seat waited while the game was in progress (seed " + seed + ")");
                }
            }
        }
    }

    /* ------------------------------------------------------------------ helpers */

    private void assertAction(Game state, String playerId, AutoPlayAction expected) {
        ViewerLegalMoves moves = state.computeViewerLegalMoves(playerId);
        AutoPlayAction action = engine.choose(state, playerId, moves);
        assertEquals(expected, action);
        assertTrue(AutoPlayLegality.isLegal(action, moves), "action must be legal: " + action);
    }

    private static void apply(Game state, String playerId, AutoPlayAction action) {
        switch (action.type()) {
            case ATTACK -> state.attack(playerId, Card.fromCode(action.cardCode()));
            case DEFEND -> state.defend(playerId, Card.fromCode(action.attackCardCode()),
                    Card.fromCode(action.cardCode()));
            case TRANSFER -> state.transfer(playerId, Card.fromCode(action.cardCode()));
            case TAKE -> state.takeCards(playerId);
            case END_ROUND -> state.endRound(playerId);
        }
    }

    /** Deals a reproducible game (Game.start shuffles with an unseeded source). */
    private static Game dealSeeded(int seed, int playerCount) {
        List<Card> deck = new ArrayList<>();
        for (Suit suit : Suit.values()) {
            for (Rank rank : Rank.values()) {
                deck.add(new Card(rank, suit));
            }
        }
        Collections.shuffle(deck, new Random(seed));
        Card trumpCard = deck.getLast();
        List<Game.PlayerSnapshot> players = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            List<Card> hand = new ArrayList<>(deck.subList(i * 6, i * 6 + 6));
            Integer team = playerCount == 4 ? i % 2 : null;
            players.add(new Game.PlayerSnapshot("p" + i, "Player " + i, i, false, team, hand, ""));
        }
        List<Card> talon = new ArrayList<>(deck.subList(playerCount * 6, deck.size()));
        int attacker = 0;
        int defender = playerCount - 1;
        return Game.fromSnapshot(new Game.Snapshot(
                "SIM" + seed, 0L, 0L, 0L, "p0", GameStatus.IN_PROGRESS, trumpCard.suit(), trumpCard,
                attacker, defender, null, false, 0, 0L, players, talon, List.of(), Set.of(), List.of(),
                List.of(), false, null, null, 0));
    }

    private static Game gameWithTrump(Suit trumpSuit) {
        return Game.fromSnapshot(new Game.Snapshot(
                "TEST01",
                0L,
                0L,
                "p",
                GameStatus.IN_PROGRESS,
                trumpSuit,
                null,
                0,
                1,
                null,
                false,
                0,
                0L,
                List.of(
                        player("p", "6H", "7C"),
                        player("q", "9C", "9D")
                ),
                List.of(),
                List.of(),
                Set.of(),
                List.of(),
                List.of()
        ));
    }

    private static Game.PlayerSnapshot player(String id, String... cardCodes) {
        List<Card> hand = Arrays.stream(cardCodes).map(Card::fromCode).toList();
        return new Game.PlayerSnapshot(id, id, 0L, false, null, hand, "");
    }

    private static List<Card> cards(String... codes) {
        return Arrays.stream(codes).map(Card::fromCode).toList();
    }

    /** Small builder for realistic in-progress states; trump is spades unless the talon says otherwise. */
    private static final class State {
        private final List<Game.PlayerSnapshot> players = new ArrayList<>();
        private final List<Game.AttackSnapshot> table = new ArrayList<>();
        private List<Card> talon = FULL_TALON;
        private List<Card> discarded = List.of();
        private Set<String> approvals = Set.of();
        private int attacker = 0;
        private int defender = 1;
        private boolean taking;
        private int takeLimit;

        State player(String id, String... hand) {
            players.add(new Game.PlayerSnapshot(id, id, players.size(), id.equals("bot"), null, cards(hand), ""));
            return this;
        }

        State attacker(int seat) {
            attacker = seat;
            return this;
        }

        State defender(int seat) {
            defender = seat;
            return this;
        }

        State talon(List<Card> cards) {
            talon = cards;
            return this;
        }

        State discarded(String... codes) {
            discarded = cards(codes);
            return this;
        }

        State defended(String attack, String defence, String attackerId) {
            table.add(new Game.AttackSnapshot(Card.fromCode(attack), Card.fromCode(defence), attackerId));
            return this;
        }

        State undefended(String attack, String attackerId) {
            table.add(new Game.AttackSnapshot(Card.fromCode(attack), null, attackerId));
            return this;
        }

        State taking(int limit) {
            taking = true;
            takeLimit = limit;
            return this;
        }

        State approvals(String... playerIds) {
            approvals = Set.of(playerIds);
            return this;
        }

        Game build() {
            return Game.fromSnapshot(new Game.Snapshot(
                    "HEUR01", 0L, 0L, 0L, players.getFirst().id(), GameStatus.IN_PROGRESS,
                    Suit.SPADES, Card.fromCode("AS"), attacker, defender, null, taking, takeLimit, 0L,
                    players, talon, table, approvals, discarded, List.of(), false, null, null, 0));
        }
    }
}
