package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Suit;
import com.example.durakgame.model.ViewerLegalMoves;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HeuristicAutoPlayDecisionEngineTest {
    private final HeuristicAutoPlayDecisionEngine engine = new HeuristicAutoPlayDecisionEngine();
    private final Game game = gameWithTrump(Suit.SPADES);

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

    private static Game gameWithTrump(Suit trumpSuit) {
        return Game.fromSnapshot(new Game.Snapshot(
                "TEST01",
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
        return new Game.PlayerSnapshot(id, id, 0L, false, null, hand);
    }
}
