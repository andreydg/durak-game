package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.AttackEntry;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.ViewerLegalMoves;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Chooses bot moves with a language model and falls back to {@link HeuristicAutoPlayDecisionEngine}.
 * Subclasses only make the model call; the decision flow around it is shared: forced moves and
 * replayed defence plans skip the model, the circuit breaker and the global call budget gate every
 * call, and the answer is validated against the legal moves.
 *
 * <p>Every returned action is legal for the {@link ViewerLegalMoves} passed in: model answers are
 * normalized and validated, and anything illegal is replaced by the heuristic's move. Each decision
 * logs one single-line {@code key=value} entry, under the concrete engine's logger.
 */
abstract class LlmAutoPlayDecisionEngine implements AutoPlayDecisionEngine {
    private static final int LOG_TEXT_LIMIT = 300;

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final HeuristicAutoPlayDecisionEngine fallback;
    private final LlmCircuitBreaker circuitBreaker;
    private final LlmCallBudget callBudget;
    private final DefencePlanCache planCache;

    protected LlmAutoPlayDecisionEngine(
            HeuristicAutoPlayDecisionEngine fallback,
            LongSupplier nanoClock,
            int circuitBreakerFailureThreshold,
            long circuitBreakerCooldownMs,
            int maxCallsPerMinute
    ) {
        this.fallback = fallback;
        this.circuitBreaker = new LlmCircuitBreaker(circuitBreakerFailureThreshold,
                Duration.ofMillis(Math.max(0, circuitBreakerCooldownMs)), nanoClock);
        this.callBudget = new LlmCallBudget(maxCallsPerMinute, nanoClock);
        this.planCache = new DefencePlanCache(DefencePlanCache.DEFAULT_MAX_PLANS, DefencePlanCache.DEFAULT_TTL,
                nanoClock);
    }

    /** Whether the model can be called at all (enabled and credentials present). */
    protected abstract boolean configured();

    /** One model round trip for the seat; never throws. */
    protected abstract ModelCall callModel(Game game, String playerId, ViewerLegalMoves legalMoves, int optionCount);

    /**
     * How a model call ended. {@code completed} means the provider answered with HTTP 2xx;
     * {@code answer} is the answer object when one could be read, and {@code noAnswerReason} the
     * decision reason logged when it could not. {@code breakerOutcome} is what the call says about
     * the service's health.
     */
    protected record ModelCall(
            boolean completed,
            JsonNode answer,
            String noAnswerReason,
            LlmCircuitBreaker.Outcome breakerOutcome,
            List<String> thoughts
    ) {
        static ModelCall failed(LlmCircuitBreaker.Outcome breakerOutcome) {
            return new ModelCall(false, null, null, breakerOutcome, List.of());
        }

        /** A completed call; a {@code null} answer is logged as {@code unparseable}. */
        static ModelCall answered(LlmCircuitBreaker.Outcome breakerOutcome, JsonNode answer, List<String> thoughts) {
            return new ModelCall(true, answer, "unparseable", breakerOutcome, thoughts);
        }

        /** A completed call that by its own account holds no answer, such as a refusal. */
        static ModelCall unanswered(LlmCircuitBreaker.Outcome breakerOutcome, String reason) {
            return new ModelCall(true, null, reason, breakerOutcome, List.of());
        }
    }

    @Override
    public AutoPlayAction choose(Game game, String playerId, ViewerLegalMoves legalMoves) {
        List<AutoPlayAction> options = AutoPlayLegality.enumerate(legalMoves);
        Decision decision = decide(game, playerId, legalMoves, options);
        log.info("autoplay_decision code={} player={} source={} reason={} action={} card={} attackCard={} "
                        + "options={}{}",
                game.getCode(), playerId, decision.source(), decision.reason(), actionType(decision.action()),
                orDash(decision.action() == null ? null : decision.action().cardCode()),
                orDash(decision.action() == null ? null : decision.action().attackCardCode()),
                options.size(), decision.detail() == null ? "" : " " + decision.detail());
        return decision.action();
    }

    /** Where an action came from and, when the heuristic was used, why. */
    private record Decision(AutoPlayAction action, String source, String reason, String detail) {
        static Decision of(AutoPlayAction action, String source, String reason) {
            return new Decision(action, source, reason, null);
        }
    }

    private Decision decide(Game game, String playerId, ViewerLegalMoves legalMoves, List<AutoPlayAction> options) {
        if (options.isEmpty()) {
            return heuristic(game, playerId, legalMoves, "no_legal_moves");
        }
        if (alreadyPassed(game, legalMoves)) {
            return Decision.of(null, "forced", "already_passed");
        }
        if (options.size() == 1 && isForced(game, options.getFirst())) {
            return Decision.of(options.getFirst(), "forced", "single_legal_option");
        }
        if (!configured()) {
            return heuristic(game, playerId, legalMoves, "disabled");
        }
        if (legalMoves.canDefend()) {
            /* The model may already have planned this defence; replay it while the table still matches. */
            AutoPlayAction planned = planCache.next(game, playerId, legalMoves);
            if (planned != null && AutoPlayLegality.isLegal(planned, legalMoves)) {
                return Decision.of(planned, "plan_cache", "none");
            }
        } else {
            planCache.discard(game.getCode(), playerId);
        }
        LlmCircuitBreaker.Permit permit = circuitBreaker.tryAcquire();
        if (permit.rejected()) {
            return heuristic(game, playerId, legalMoves, "circuit_open");
        }
        if (!callBudget.tryAcquire()) {
            circuitBreaker.record(permit, LlmCircuitBreaker.Outcome.NEUTRAL);
            return heuristic(game, playerId, legalMoves, "budget_exhausted");
        }
        ModelCall call = callModel(game, playerId, legalMoves, options.size());
        circuitBreaker.record(permit, call.breakerOutcome());
        if (!call.completed()) {
            return heuristic(game, playerId, legalMoves, "primary_model_failed");
        }
        if (call.answer() == null) {
            return heuristic(game, playerId, legalMoves, call.noAnswerReason());
        }
        MoveAnswer answer = new MoveAnswer(call.answer());
        logStrategy(game, playerId, answer, call.thoughts());
        Set<String> undefended = undefendedAttacks(game, legalMoves);
        AutoPlayAction action = answer.resolve(legalMoves, undefended);
        if (action == null || !AutoPlayLegality.isLegal(action, legalMoves)) {
            Decision fallbackDecision = heuristic(game, playerId, legalMoves, "illegal_model_action");
            return new Decision(fallbackDecision.action(), fallbackDecision.source(), fallbackDecision.reason(),
                    "model=" + answer.describe());
        }
        if (action.type() == AutoPlayAction.Type.DEFEND) {
            planCache.store(game, playerId, legalMoves, action, answer.defencePlan(undefended));
        }
        return Decision.of(action, "llm", "none");
    }

    /**
     * A lone legal option needs no model call, unless it is an optional throw-in: then "wait" is the
     * unlisted alternative (for example after the bot already passed) and the choice stays open.
     */
    private static boolean isForced(Game game, AutoPlayAction onlyOption) {
        return onlyOption.type() != AutoPlayAction.Type.ATTACK || game.getTable().isEmpty();
    }

    /**
     * The bot already passed (END_ROUND) in this bout and nothing changed since: any new card on the
     * table clears every pass. Only throw-ins are left to choose from, and playing one would contradict
     * that pass, so the bot waits for the other players instead of asking the model again.
     */
    private static boolean alreadyPassed(Game game, ViewerLegalMoves legalMoves) {
        if (legalMoves.canEndRound() || !legalMoves.canAttack()) {
            return false;
        }
        List<AttackEntry> table = game.getTable();
        return !table.isEmpty()
                && (game.isTakingCardsInProgress() || table.stream().allMatch(AttackEntry::isDefended));
    }

    private Decision heuristic(Game game, String playerId, ViewerLegalMoves legalMoves, String reason) {
        return Decision.of(fallback.choose(game, playerId, legalMoves), "heuristic", reason);
    }

    private static Set<String> undefendedAttacks(Game game, ViewerLegalMoves legalMoves) {
        Set<String> attacks = new LinkedHashSet<>();
        for (AttackEntry entry : game.getTable()) {
            if (!entry.isDefended()) {
                attacks.add(entry.getAttackCard().code());
            }
        }
        attacks.addAll(legalMoves.defensesByAttackCard().keySet());
        return attacks;
    }

    /** Package-private for tests. */
    LlmCircuitBreaker.State circuitState() {
        return circuitBreaker.state();
    }

    /** The global call budget's burst size, for the engines' configuration log lines. */
    protected int callBurst() {
        return callBudget.burst();
    }

    /* ------------------------------------------------------------------ logging */

    private void logStrategy(Game game, String playerId, MoveAnswer answer, List<String> thoughts) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("autoplay_llm_strategy code={} player={} strategy=\"{}\" thoughts=\"{}\"",
                game.getCode(), playerId, sanitize(answer.strategy()), sanitize(String.join(" ", thoughts)));
    }

    /** Removes credentials from text that is about to be logged; the default has none to remove. */
    protected String redact(String text) {
        return text;
    }

    /** One line, bounded, quote-safe, and never containing a credential. */
    protected String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = redact(text).replaceAll("\\p{Cntrl}+", " ").replace('"', '\'').trim();
        return cleaned.length() > LOG_TEXT_LIMIT ? cleaned.substring(0, LOG_TEXT_LIMIT) + "..." : cleaned;
    }

    private static String actionType(AutoPlayAction action) {
        return action == null ? "none" : action.type().name();
    }

    protected static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
