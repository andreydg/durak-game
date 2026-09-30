package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.service.autoplay.GeminiAnswer.DefencePair;
import com.example.durakgame.service.autoplay.GeminiAutoPlayDecisionEngineTest.GameBuilder;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.example.durakgame.service.autoplay.GeminiAutoPlayDecisionEngineTest.BOT;
import static com.example.durakgame.service.autoplay.GeminiAutoPlayDecisionEngineTest.HUMAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefencePlanCacheTest {
    private final AtomicLong clock = new AtomicLong();
    private final DefencePlanCache cache = new DefencePlanCache(16, Duration.ofMinutes(2), clock::get);

    private static final AutoPlayAction FIRST = AutoPlayAction.defend("7H", "KH");
    private static final List<DefencePair> PLAN = List.of(
            new DefencePair("7H", "KH"),
            new DefencePair("9C", "QC"));

    /** The human attacked with 7H and 9C; the bot (defender) can beat 7H with KH/8S and 9C with QC/8S. */
    private static Game twoAttacks(int boutsCompleted, String... botHand) {
        return new GameBuilder()
                .player(HUMAN, "Human", false, "9D", "7D", "JS")
                .player(BOT, "Bot", true, botHand)
                .talon("6C", "8D", "AS")
                .bouts(boutsCompleted)
                .undefended("7H", HUMAN)
                .undefended("9C", HUMAN)
                .build();
    }

    private static Game twoAttacks() {
        return twoAttacks(3, "KH", "QC", "8S", "6D");
    }

    private boolean store(Game game, List<DefencePair> plan, AutoPlayAction chosen) {
        return cache.store(game, BOT, game.computeViewerLegalMoves(BOT), chosen, plan);
    }

    private AutoPlayAction next(Game game) {
        return cache.next(game, BOT, game.computeViewerLegalMoves(BOT));
    }

    @Test
    void replaysTheRemainingPairWhileTheTableMatches() {
        Game game = twoAttacks();
        assertTrue(store(game, PLAN, FIRST));

        game.defend(BOT, Card.fromCode("7H"), Card.fromCode("KH"));

        assertEquals(AutoPlayAction.defend("9C", "QC"), next(game));
        assertEquals(0, cache.size(), "a fully used plan is dropped");
    }

    @Test
    void aNewThrowInDiscardsThePlan() {
        Game game = twoAttacks();
        store(game, PLAN, FIRST);
        game.defend(BOT, Card.fromCode("7H"), Card.fromCode("KH"));

        game.attack(HUMAN, Card.fromCode("9D"));

        assertNull(next(game));
        assertEquals(0, cache.size());
    }

    @Test
    void aStaleTableBeforeTheFirstDefenceDoesNotMatch() {
        Game game = twoAttacks();
        store(game, PLAN, FIRST);

        // The first planned defence was never applied (for example the move was rejected).
        assertNull(next(game));
    }

    @Test
    void aNewBoutDiscardsThePlan() {
        store(twoAttacks(), PLAN, FIRST);
        Game later = twoAttacks(4, "KH", "QC", "8S", "6D");
        later.defend(BOT, Card.fromCode("7H"), Card.fromCode("KH"));

        assertNull(next(later));
    }

    @Test
    void aPlannedCardThatLeftTheHandDiscardsThePlan() {
        store(twoAttacks(), PLAN, FIRST);
        Game withoutQueen = twoAttacks(3, "KH", "8S", "6D", "10C");
        withoutQueen.defend(BOT, Card.fromCode("7H"), Card.fromCode("KH"));

        assertNull(next(withoutQueen));
    }

    @Test
    void storesOnlyCompleteConsistentPlans() {
        Game game = twoAttacks();

        assertFalse(store(game, List.of(new DefencePair("7H", "KH")), FIRST), "must cover every attack");
        assertFalse(store(game, List.of(new DefencePair("7H", "8S"), new DefencePair("9C", "8S")),
                AutoPlayAction.defend("7H", "8S")), "one card cannot beat two attacks");
        assertFalse(store(game, PLAN, AutoPlayAction.defend("7H", "8S")), "must contain the chosen pair");
        assertFalse(store(game, List.of(new DefencePair("7H", "KH"), new DefencePair("9C", "KH")), FIRST),
                "KH does not beat 9C");
        assertFalse(store(game, PLAN, AutoPlayAction.take()));

        Game single = new GameBuilder()
                .player(HUMAN, "Human", false, "9D")
                .player(BOT, "Bot", true, "KH", "8S")
                .talon("6C", "AS")
                .undefended("7H", HUMAN)
                .build();
        assertFalse(cache.store(single, BOT, single.computeViewerLegalMoves(BOT), FIRST,
                List.of(new DefencePair("7H", "KH"))), "a single attack leaves nothing to reuse");
        assertEquals(0, cache.size());
    }

    @Test
    void plansExpire() {
        Game game = twoAttacks();
        store(game, PLAN, FIRST);
        game.defend(BOT, Card.fromCode("7H"), Card.fromCode("KH"));

        clock.addAndGet(Duration.ofMinutes(2).plusSeconds(1).toNanos());

        assertNull(next(game));
    }

    @Test
    void theCacheIsBounded() {
        DefencePlanCache small = new DefencePlanCache(2, Duration.ofMinutes(2), clock::get);
        for (String code : List.of("GAME01", "GAME02", "GAME03")) {
            Game game = new GameBuilder()
                    .code(code)
                    .player(HUMAN, "Human", false, "9D", "7D", "JS")
                    .player(BOT, "Bot", true, "KH", "QC", "8S", "6D")
                    .talon("6C", "8D", "AS")
                    .undefended("7H", HUMAN)
                    .undefended("9C", HUMAN)
                    .build();
            assertTrue(small.store(game, BOT, game.computeViewerLegalMoves(BOT), FIRST, PLAN));
        }

        assertEquals(2, small.size());
    }
}
