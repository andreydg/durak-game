package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.AttackEntry;
import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.Player;
import com.example.durakgame.model.ViewerLegalMoves;
import com.example.durakgame.service.autoplay.GeminiAnswer.DefencePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Remembers the rest of a multi-attack defence the model already planned, so the bot's next defend
 * decision in the same bout replays it instead of calling the model again.
 *
 * <p>A plan is replayed only while the table matches it exactly: same bout, the undefended attacks
 * are precisely the ones still planned, and every planned defence is still legal and in hand. Any
 * difference (a new throw-in, a transfer, a new bout, a card played elsewhere) discards the plan so
 * the model is asked again. Entries are bounded by count and age. Thread-safe.
 */
final class DefencePlanCache {
    private static final Logger log = LoggerFactory.getLogger(DefencePlanCache.class);

    static final int DEFAULT_MAX_PLANS = 1024;
    static final Duration DEFAULT_TTL = Duration.ofMinutes(2);

    private record Key(String gameCode, String playerId) {
    }

    private record Plan(int boutsCompleted, List<DefencePair> remaining, Set<String> expectedUndefended,
                        long storedAtNanos) {
    }

    private final int maxPlans;
    private final long ttlNanos;
    private final LongSupplier nanoClock;
    private final Map<Key, Plan> plans;

    DefencePlanCache(int maxPlans, Duration ttl, LongSupplier nanoClock) {
        this.maxPlans = Math.max(1, maxPlans);
        this.ttlNanos = ttl.toNanos();
        this.nanoClock = nanoClock;
        this.plans = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Plan> eldest) {
                return size() > DefencePlanCache.this.maxPlans;
            }
        };
    }

    /**
     * Keeps the rest of the model's plan after it chose {@code chosen}. The plan must assign distinct,
     * currently legal defence cards to every undefended attack exactly once, include the chosen pair,
     * and leave at least one pair; otherwise nothing is stored. Returns whether a plan was stored.
     */
    synchronized boolean store(Game game, String playerId, ViewerLegalMoves moves, AutoPlayAction chosen,
                               List<DefencePair> plan) {
        Key key = new Key(game.getCode(), playerId);
        plans.remove(key);
        if (chosen == null || chosen.type() != AutoPlayAction.Type.DEFEND || plan == null) {
            return false;
        }
        Set<String> undefended = undefendedAttacks(game);
        if (undefended.size() < 2 || plan.size() != undefended.size()) {
            return false;
        }
        Set<String> plannedAttacks = new HashSet<>();
        Set<String> plannedDefences = new HashSet<>();
        boolean includesChosen = false;
        for (DefencePair pair : plan) {
            if (!plannedAttacks.add(pair.attackCardCode()) || !plannedDefences.add(pair.cardCode())
                    || !AutoPlayLegality.defencesFor(moves, pair.attackCardCode()).contains(pair.cardCode())) {
                return false;
            }
            includesChosen |= pair.attackCardCode().equals(chosen.attackCardCode())
                    && pair.cardCode().equals(chosen.cardCode());
        }
        if (!includesChosen || !plannedAttacks.equals(undefended)) {
            return false;
        }
        List<DefencePair> remaining = plan.stream()
                .filter(pair -> !pair.attackCardCode().equals(chosen.attackCardCode()))
                .toList();
        Set<String> expected = new LinkedHashSet<>(undefended);
        expected.remove(chosen.attackCardCode());
        plans.put(key, new Plan(game.getBoutsCompleted(), remaining, Set.copyOf(expected), nanoClock.getAsLong()));
        log.debug("autoplay_plan_cache event=stored code={} player={} remainingPairs={}",
                game.getCode(), playerId, remaining.size());
        return true;
    }

    /** The next planned defence while the table still matches the plan; otherwise the plan is dropped. */
    synchronized AutoPlayAction next(Game game, String playerId, ViewerLegalMoves moves) {
        Key key = new Key(game.getCode(), playerId);
        Plan plan = plans.get(key);
        if (plan == null) {
            return null;
        }
        String mismatch = mismatch(plan, game, playerId, moves);
        if (mismatch != null) {
            plans.remove(key);
            log.debug("autoplay_plan_cache event=discarded code={} player={} reason={}",
                    game.getCode(), playerId, mismatch);
            return null;
        }
        DefencePair next = plan.remaining().getFirst();
        List<DefencePair> rest = plan.remaining().subList(1, plan.remaining().size());
        if (rest.isEmpty()) {
            plans.remove(key);
        } else {
            Set<String> expected = new HashSet<>(plan.expectedUndefended());
            expected.remove(next.attackCardCode());
            plans.put(key, new Plan(plan.boutsCompleted(), List.copyOf(rest), Set.copyOf(expected),
                    plan.storedAtNanos()));
        }
        return AutoPlayAction.defend(next.attackCardCode(), next.cardCode());
    }

    synchronized void discard(String gameCode, String playerId) {
        plans.remove(new Key(gameCode, playerId));
    }

    synchronized int size() {
        return plans.size();
    }

    private String mismatch(Plan plan, Game game, String playerId, ViewerLegalMoves moves) {
        if (nanoClock.getAsLong() - plan.storedAtNanos() > ttlNanos) {
            return "expired";
        }
        if (game.getBoutsCompleted() != plan.boutsCompleted()) {
            return "new_bout";
        }
        if (!moves.canDefend() || !undefendedAttacks(game).equals(plan.expectedUndefended())) {
            return "table_changed";
        }
        List<Card> hand = game.getPlayers().stream()
                .filter(player -> Objects.equals(player.getId(), playerId))
                .findFirst()
                .map(Player::getHand)
                .orElse(List.of());
        List<String> handCodes = new ArrayList<>(hand.size());
        hand.forEach(card -> handCodes.add(card.code()));
        for (DefencePair pair : plan.remaining()) {
            if (!handCodes.contains(pair.cardCode())
                    || !AutoPlayLegality.defencesFor(moves, pair.attackCardCode()).contains(pair.cardCode())) {
                return "defence_no_longer_legal";
            }
        }
        return null;
    }

    private static Set<String> undefendedAttacks(Game game) {
        Set<String> undefended = new LinkedHashSet<>();
        for (AttackEntry entry : game.getTable()) {
            if (!entry.isDefended()) {
                undefended.add(entry.getAttackCard().code());
            }
        }
        return undefended;
    }
}
