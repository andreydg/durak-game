package com.example.durakgame.model;

import com.example.durakgame.service.autoplay.AutoPlayAction;
import com.example.durakgame.service.autoplay.HeuristicAutoPlayDecisionEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules-engine fuzzer.
 *
 * <p>Plays seeded random games through the public {@link Game} API. Every seat is driven by a
 * random-legal-move policy built from {@link Game#computeViewerLegalMoves} (the same source the
 * UI, {@code GameService} and {@code HeuristicAutoPlayDecisionEngine} use). After EVERY action the
 * full state is checked against rule invariants. In "probe" mode the harness additionally fires
 * arbitrary (often illegal) actions at a copy of the game and checks that (a) rejected actions do
 * not change state and (b) every accepted action was advertised as legal.
 *
 * <p>Deals are reproducible: seeded games build the start position with a seeded shuffle that
 * mirrors {@code Game.start()} and load it via {@link Game#fromSnapshot}; real-{@code start()}
 * games log their initial position. The default run is sized for CI; for a deep sweep run:
 * <pre>./mvnw -q test -Dtest=DurakRulesFuzzTest -Dfuzz.games=4000 -Dfuzz.seed=424242</pre>
 * Reports (violations plus informational counters) are written to {@code target/fuzz-reports/}.
 */
class DurakRulesFuzzTest {

    private static final long BASE_SEED = Long.getLong("fuzz.seed", 20260927L);
    private static final int GAMES_PER_CONFIG = Integer.getInteger("fuzz.games", 150);
    private static final int MAX_ACTIONS_PER_GAME = 50_000;
    private static final int MIN_PLAYERS = 2;
    /* GameService caps rooms at 4; the model itself can deal 5x6 cards (6 players exhausts the deck). */
    private static final int MAX_PLAYERS = Integer.getInteger("fuzz.maxPlayers", 5);
    private static final int PROBE_PCT = 35;
    private static final int APPLY_CHECK_PCT = 5;
    private static final int ROUNDTRIP_CHECK_PCT = 5;
    private static final int DECK_SIZE = 36;
    private static final List<Card> FULL_DECK = fullDeck();
    private static final Set<Card> FULL_DECK_SET = Set.copyOf(FULL_DECK);
    private static final HeuristicAutoPlayDecisionEngine HEURISTIC = new HeuristicAutoPlayDecisionEngine();

    enum Type { ATTACK, DEFEND, TRANSFER, TAKE, END_ROUND }

    enum Policy { UNIFORM, BY_TYPE, HEURISTIC_MIX }

    record Act(int seat, String playerId, Type type, Card card, Card attackCard) {
        @Override
        public String toString() {
            return switch (type) {
                case ATTACK -> "p" + seat + ".attack(" + card.code() + ")";
                case DEFEND -> "p" + seat + ".defend(" + attackCard.code() + "<-" + card.code() + ")";
                case TRANSFER -> "p" + seat + ".transfer(" + card.code() + ")";
                case TAKE -> "p" + seat + ".take()";
                case END_ROUND -> "p" + seat + ".endRound()";
            };
        }
    }

    // ------------------------------------------------------------------ fuzz entry points

    @Test
    void fuzzSeededGamesWithAdvertisedMovesOnly() {
        Stats st = new Stats("seeded deals, advertised moves only");
        for (int n = MIN_PLAYERS; n <= MAX_PLAYERS; n++) {
            for (Policy policy : Policy.values()) {
                for (int i = 0; i < GAMES_PER_CONFIG; i++) {
                    long seed = BASE_SEED + n * 1_000_000L + policy.ordinal() * 100_000L + i;
                    Random rnd = new Random(seed);
                    Run run = new Run("seeded n=" + n + " policy=" + policy + " probes=false seed=" + seed,
                            n, rnd, seededGame(n, rnd));
                    play(st, run, policy, false);
                }
            }
        }
        finish(st, "fuzz-advertised.txt");
    }

    @Test
    void fuzzSeededGamesWithIllegalProbes() {
        Stats st = new Stats("seeded deals, advertised moves + arbitrary probe actions");
        for (int n = MIN_PLAYERS; n <= MAX_PLAYERS; n++) {
            for (Policy policy : Policy.values()) {
                for (int i = 0; i < GAMES_PER_CONFIG / 2; i++) {
                    long seed = 50_000_000L + BASE_SEED + n * 1_000_000L + policy.ordinal() * 100_000L + i;
                    Random rnd = new Random(seed);
                    Run run = new Run("seeded n=" + n + " policy=" + policy + " probes=true seed=" + seed,
                            n, rnd, seededGame(n, rnd));
                    play(st, run, policy, true);
                }
            }
        }
        finish(st, "fuzz-probes.txt");
    }

    @Test
    void fuzzRealStartDeals() {
        Stats st = new Stats("Game.start() deals (unseeded shuffle, seeded policy)");
        for (int n = MIN_PLAYERS; n <= MAX_PLAYERS; n++) {
            for (int i = 0; i < GAMES_PER_CONFIG / 3; i++) {
                long seed = 90_000_000L + BASE_SEED + n * 1_000_000L + i;
                Game game = new Game("REAL" + i, new Player("P0"));
                for (int k = 1; k < n; k++) {
                    game.addPlayer("P" + k, 10);
                }
                long versionBefore = game.getVersion();
                game.start(game.getHostPlayerId());
                Run run = new Run("start() n=" + n + " seed=" + seed, n, new Random(seed), game);
                checkRealStart(st, run, game.toSnapshot(), versionBefore);
                play(st, run, Policy.values()[i % Policy.values().length], false);
            }
        }
        finish(st, "fuzz-start.txt");
    }

    /**
     * Replays one seeded game and prints its full action log, e.g.
     * {@code -Dfuzz.replay=72260927:2:UNIFORM:true} (seed:players:policy:probes, as printed in reports).
     */
    @Test
    void replaySingleSeededGame() {
        String spec = System.getProperty("fuzz.replay");
        org.junit.jupiter.api.Assumptions.assumeTrue(spec != null && !spec.isBlank(), "set -Dfuzz.replay");
        String[] parts = spec.split(":");
        long seed = Long.parseLong(parts[0]);
        int n = Integer.parseInt(parts[1]);
        Policy policy = Policy.valueOf(parts[2]);
        boolean probes = Boolean.parseBoolean(parts[3]);
        Stats st = new Stats("replay " + spec);
        Random rnd = new Random(seed);
        Run run = new Run("replay n=" + n + " policy=" + policy + " probes=" + probes + " seed=" + seed,
                n, rnd, seededGame(n, rnd));
        play(st, run, policy, probes);
        System.out.println("initial: " + run.initialState);
        for (int i = 0; i < run.log.size(); i++) {
            System.out.println("  " + (i + 1) + ": " + run.log.get(i));
        }
        finish(st, "fuzz-replay.txt");
    }

    // ------------------------------------------------------------------ game loop

    private void play(Stats st, Run run, Policy policy, boolean probes) {
        Game.Snapshot initial = run.game.toSnapshot();
        run.initialState = compact(initial);
        checkState(st, run, run.game, initial);
        if (initial.trumpSuit() != null && initial.players().stream()
                .noneMatch(p -> p.hand().stream().anyMatch(c -> c.suit() == initial.trumpSuit()))) {
            st.info(run, "INFO: nobody was dealt a trump; seat 0 leads (fallback in chooseFirstAttacker)", "");
        }
        while (run.game.getStatus() == GameStatus.IN_PROGRESS) {
            if (run.actions >= MAX_ACTIONS_PER_GAME) {
                st.violation(run, "NONTERMINATION", "exceeded " + MAX_ACTIONS_PER_GAME + " actions");
                break;
            }
            Game.Snapshot before = run.game.toSnapshot();
            List<Act> moves = advertisedMoves(st, run, run.game, before);
            if (moves.isEmpty()) {
                st.violation(run, "STALL_NO_PLAYER_HAS_A_LEGAL_MOVE", compact(before));
                break;
            }
            if (run.rnd.nextInt(100) < APPLY_CHECK_PCT) {
                checkAdvertisedMovesApply(st, run, before, moves);
            }
            if (run.rnd.nextInt(100) < ROUNDTRIP_CHECK_PCT) {
                checkSnapshotRoundTrip(st, run, before);
            }
            if (probes && run.rnd.nextInt(100) < PROBE_PCT) {
                probe(st, run, before, moves);
                continue;
            }
            Act act = choose(st, run, policy, before, moves);
            try {
                apply(run.game, act);
            } catch (RuntimeException ex) {
                st.violation(run, "ADVERTISED_MOVE_REJECTED", act + " -> " + ex);
                break;
            }
            run.log.add(act.toString());
            afterSuccess(st, run, act, before, run.game.toSnapshot());
        }
        st.endGame(run);
    }

    private void probe(Stats st, Run run, Game.Snapshot before, List<Act> moves) {
        st.probes++;
        Act p = randomProbe(run, before);
        boolean advertised = moves.contains(p);
        Game copy = Game.fromSnapshot(before);
        Map<String, String> canonBefore = canon(before);
        try {
            apply(copy, p);
        } catch (RuntimeException ex) {
            st.probesRejected++;
            if (advertised) {
                st.violation(run, "ADVERTISED_MOVE_REJECTED", p + " -> " + ex);
            }
            Game.Snapshot after = copy.toSnapshot();
            Map<String, String> canonAfter = canon(after);
            if (!canonAfter.equals(canonBefore)) {
                List<String> changed = new ArrayList<>();
                for (String key : canonBefore.keySet()) {
                    if (!Objects.equals(canonBefore.get(key), canonAfter.get(key))) {
                        changed.add(key);
                    }
                }
                st.violation(run, "REJECTED_" + p.type() + "_MUTATED_STATE" + changed,
                        p + " threw " + ex.getClass().getSimpleName() + "(\"" + ex.getMessage() + "\") but changed "
                                + changed + ": " + diff(before, after) + " | before: " + compact(before));
            }
            return;
        }
        if (!advertised) {
            boolean repeatApproval = p.type() == Type.END_ROUND && before.endRoundApprovals().contains(p.playerId());
            if (repeatApproval) {
                st.info(run, "BENIGN: repeated endRound approval accepted (idempotent, bumps version)", p.toString());
            } else {
                String phase = before.takingCardsInProgress() ? "while defender is taking" : "normal phase";
                st.violation(run, "UNADVERTISED_" + p.type() + "_ACCEPTED[" + phase + "]",
                        p + " accepted although computeViewerLegalMoves did not offer it | before: " + compact(before));
                run.tainted = true;
            }
        }
        run.game = copy;
        run.log.add(p + (advertised ? "" : " [unadvertised]"));
        afterSuccess(st, run, p, before, copy.toSnapshot());
    }

    private Act randomProbe(Run run, Game.Snapshot s) {
        Random r = run.rnd;
        int n = s.players().size();
        int seat = switch (r.nextInt(3)) {
            case 0 -> s.defenderIndex();
            case 1 -> s.attackerIndex();
            default -> r.nextInt(n);
        };
        String id = s.players().get(seat).id();
        Type type = Type.values()[r.nextInt(Type.values().length)];
        List<Card> hand = s.players().get(seat).hand();
        Card card = (!hand.isEmpty() && r.nextInt(10) < 8)
                ? hand.get(r.nextInt(hand.size()))
                : FULL_DECK.get(r.nextInt(DECK_SIZE));
        Card attackCard = null;
        if (type == Type.DEFEND) {
            List<Card> attacks = s.table().stream().map(Game.AttackSnapshot::attackCard).toList();
            attackCard = (!attacks.isEmpty() && r.nextInt(10) < 8)
                    ? attacks.get(r.nextInt(attacks.size()))
                    : FULL_DECK.get(r.nextInt(DECK_SIZE));
        }
        boolean needsCard = type == Type.ATTACK || type == Type.DEFEND || type == Type.TRANSFER;
        return new Act(seat, id, type, needsCard ? card : null, attackCard);
    }

    private Act choose(Stats st, Run run, Policy policy, Game.Snapshot s, List<Act> moves) {
        Random r = run.rnd;
        switch (policy) {
            case UNIFORM:
                return moves.get(r.nextInt(moves.size()));
            case BY_TYPE: {
                Map<Type, List<Act>> byType = new EnumMap<>(Type.class);
                for (Act m : moves) {
                    byType.computeIfAbsent(m.type(), k -> new ArrayList<>()).add(m);
                }
                List<Type> types = new ArrayList<>(byType.keySet());
                List<Act> pool = byType.get(types.get(r.nextInt(types.size())));
                return pool.get(r.nextInt(pool.size()));
            }
            case HEURISTIC_MIX:
            default: {
                List<Integer> seats = moves.stream().map(Act::seat).distinct().sorted().toList();
                int seat = seats.get(r.nextInt(seats.size()));
                List<Act> mine = moves.stream().filter(m -> m.seat() == seat).toList();
                if (r.nextBoolean()) {
                    String id = s.players().get(seat).id();
                    AutoPlayAction a = HEURISTIC.choose(run.game, id, run.game.computeViewerLegalMoves(id));
                    if (a != null) {
                        Act act = fromAutoPlay(seat, id, a);
                        if (mine.contains(act)) {
                            return act;
                        }
                        st.violation(run, "HEURISTIC_CHOSE_UNADVERTISED_MOVE", a + " | " + compact(s));
                    }
                }
                return mine.get(r.nextInt(mine.size()));
            }
        }
    }

    // ------------------------------------------------------------------ per-action checks

    private void afterSuccess(Stats st, Run run, Act act, Game.Snapshot before, Game.Snapshot after) {
        st.actions++;
        run.actions++;
        if (after.version() != before.version() + 1) {
            st.violation(run, "VERSION_NOT_INCREMENTED_BY_ONE", before.version() + " -> " + after.version());
        }
        int d = before.defenderIndex();
        switch (act.type()) {
            case ATTACK -> {
                if (act.seat() == d || teammate(before, act.seat(), d)) {
                    st.violation(run, "ATTACK_BY_DEFENDING_SIDE", act + " | " + compact(before));
                }
                if (before.table().isEmpty()) {
                    if (act.seat() != before.attackerIndex()) {
                        st.violation(run, "OPENING_LEAD_BY_NON_ATTACKER", act + " | " + compact(before));
                    }
                    run.h0 = hand(before, d).size();
                    run.boutFlags.clear();
                } else if (!tableRanks(before).contains(act.card().rank())) {
                    st.violation(run, "THROW_IN_RANK_NOT_ON_TABLE", act + " | " + compact(before));
                }
                if (before.takingCardsInProgress() && before.table().size() >= before.takeLimit()) {
                    st.violation(run, "THROW_IN_BEYOND_TAKE_LIMIT", act + " | " + compact(before));
                }
            }
            case TRANSFER -> {
                int expectedDefender = nextEligibleDefender(before, d);
                if (after.attackerIndex() != d || after.defenderIndex() != expectedDefender) {
                    st.violation(run, "TRANSFER_ROLES", act + " expected a=" + d + " d=" + expectedDefender
                            + " got a=" + after.attackerIndex() + " d=" + after.defenderIndex());
                }
                if (before.table().stream().anyMatch(t -> t.defenseCard() != null)) {
                    st.violation(run, "TRANSFER_AFTER_DEFENSE_STARTED", act + " | " + compact(before));
                }
                if (act.card().rank() != before.table().getFirst().attackCard().rank()) {
                    st.violation(run, "TRANSFER_RANK_MISMATCH", act + " | " + compact(before));
                }
                run.h0 = hand(before, after.defenderIndex()).size();
            }
            case END_ROUND -> {
                if (!before.table().isEmpty() && after.table().isEmpty()) {
                    boutEnded(st, run, act, before, after);
                }
            }
            default -> {
            }
        }
        checkState(st, run, run.game, after);
    }

    private void boutEnded(Stats st, Run run, Act act, Game.Snapshot before, Game.Snapshot after) {
        int n = before.players().size();
        int a = before.attackerIndex();
        int d = before.defenderIndex();
        boolean took = before.takingCardsInProgress();

        Set<String> approvals = new HashSet<>(before.endRoundApprovals());
        approvals.add(act.playerId());
        for (int i = 0; i < n; i++) {
            boolean out = before.talon().isEmpty() && hand(before, i).isEmpty();
            if (!out && attackingSide(before, i) && !approvals.contains(before.players().get(i).id())) {
                st.violation(run, "BOUT_ENDED_WITHOUT_ALL_APPROVALS", "missing p" + i + " | " + compact(before));
            }
        }
        if (!took && before.table().stream().anyMatch(t -> t.defenseCard() == null)) {
            st.violation(run, "DISCARDED_BOUT_WITH_UNDEFENDED_CARD", compact(before));
        }
        List<Card> tableCards = tableCards(before);
        if (took) {
            if (!new HashSet<>(hand(after, d)).containsAll(tableCards)) {
                st.violation(run, "TAKER_DID_NOT_RECEIVE_TABLE", compact(before) + " -> " + compact(after));
            }
        } else if (!new HashSet<>(after.discardedCards()).containsAll(tableCards)) {
            st.violation(run, "TABLE_NOT_DISCARDED", compact(before) + " -> " + compact(after));
        }

        // Draw order: engine order (attacker, then every seat in play order incl. defender) vs
        // standard order (attacker, other attackers in play order, defender last).
        List<List<Card>> pre = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            pre.add(new ArrayList<>(hand(before, i)));
        }
        if (took) {
            pre.get(d).addAll(tableCards);
        }
        List<Integer> engineOrder = new ArrayList<>();
        int idx = a;
        do {
            engineOrder.add(idx);
            idx = prev(idx, n);
        } while (idx != a);
        List<Integer> standardOrder = new ArrayList<>(engineOrder);
        standardOrder.remove(Integer.valueOf(d));
        standardOrder.add(d);
        List<Set<Card>> engineHands = simulateRefill(pre, before.talon(), engineOrder);
        List<Set<Card>> standardHands = simulateRefill(pre, before.talon(), standardOrder);
        List<Set<Card>> actualHands = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            actualHands.add(new HashSet<>(hand(after, i)));
        }
        if (!engineHands.equals(actualHands)) {
            st.violation(run, "REFILL_DIFFERS_FROM_MODELLED_ENGINE_ORDER", compact(before) + " -> " + compact(after));
        }
        List<Integer> actualSizes = actualHands.stream().map(Set::size).toList();
        List<Integer> standardSizes = standardHands.stream().map(Set::size).toList();
        String drawDetail = "attacker=p" + a + " defender=p" + d + " talon=" + codes(before.talon()) + " took=" + took
                + " | pre-refill hand sizes=" + pre.stream().map(List::size).toList()
                + " actual=" + actualSizes + " standard(defender last)=" + standardSizes;
        if (!standardSizes.equals(actualSizes)) {
            st.info(run, "STD_DEVIATION: talon ran short and the defender drew before another attacker (hand sizes differ)",
                    drawDetail);
        } else if (before.trumpCard() != null && before.talon().contains(before.trumpCard())
                && holderOf(standardHands, before.trumpCard()) != holderOf(actualHands, before.trumpCard())) {
            st.info(run, "STD_DEVIATION: face-up trump card went to a different player than under defender-draws-last",
                    drawDetail);
        }

        if (after.status() == GameStatus.IN_PROGRESS) {
            int expectedAttacker = took
                    ? nextEligible(after, d)
                    : (isOut(after, d) ? nextEligible(after, d) : d);
            int expectedDefender = nextEligibleDefender(after, expectedAttacker);
            if (after.attackerIndex() != expectedAttacker || after.defenderIndex() != expectedDefender) {
                st.violation(run, "NEXT_ROLES_AFTER_BOUT", "took=" + took + " expected a=" + expectedAttacker
                        + " d=" + expectedDefender + " got a=" + after.attackerIndex() + " d=" + after.defenderIndex()
                        + " | " + compact(after));
            }
        }
        if (took) {
            st.takes++;
        } else {
            st.discards++;
        }
        run.h0 = -1;
        run.firstBout = false;
        run.boutFlags.clear();
    }

    private void checkState(Stats st, Run run, Game game, Game.Snapshot s) {
        List<Card> all = new ArrayList<>();
        for (Game.PlayerSnapshot p : s.players()) {
            all.addAll(p.hand());
        }
        all.addAll(s.talon());
        all.addAll(tableCards(s));
        all.addAll(s.discardedCards());
        if (all.size() != DECK_SIZE) {
            st.violation(run, "CARD_COUNT_NOT_36", all.size() + " cards | " + compact(s));
        }
        Set<Card> distinct = new HashSet<>(all);
        if (distinct.size() != all.size()) {
            st.violation(run, "DUPLICATE_CARD", compact(s));
        }
        if (!distinct.equals(FULL_DECK_SET)) {
            Set<Card> missing = new HashSet<>(FULL_DECK_SET);
            missing.removeAll(distinct);
            st.violation(run, "CARD_SET_NOT_FULL_DECK", "missing=" + codes(missing) + " | " + compact(s));
        }
        if (s.trumpCard() == null || s.trumpSuit() != s.trumpCard().suit()) {
            st.violation(run, "TRUMP_INCONSISTENT", compact(s));
        } else if (!s.talon().isEmpty() && !s.talon().getLast().equals(s.trumpCard())) {
            st.violation(run, "TRUMP_CARD_NOT_LAST_IN_TALON", compact(s));
        }
        for (Game.KnownCardsSnapshot known : s.knownCardsByPlayer()) {
            int seat = seatOf(s, known.playerId());
            if (seat < 0 || !new HashSet<>(hand(s, seat)).containsAll(known.cards())) {
                st.violation(run, "KNOWN_CARDS_NOT_IN_HAND", known + " | " + compact(s));
            }
        }
        for (Game.AttackSnapshot t : s.table()) {
            if (t.defenseCard() != null && !beats(t.attackCard(), t.defenseCard(), s.trumpSuit())) {
                st.violation(run, "ILLEGAL_BEAT_ON_TABLE", t.attackCard().code() + "<-" + t.defenseCard().code()
                        + " trump=" + s.trumpSuit());
            }
        }

        if (s.status() == GameStatus.IN_PROGRESS) {
            int n = s.players().size();
            int a = s.attackerIndex();
            int d = s.defenderIndex();
            if (a < 0 || a >= n || d < 0 || d >= n || a == d) {
                st.violation(run, "ROLES_INVALID", "a=" + a + " d=" + d + " | " + compact(s));
                return;
            }
            if (teammate(s, a, d)) {
                st.violation(run, "ATTACKER_AND_DEFENDER_ARE_TEAMMATES", compact(s));
            }
            for (String approver : s.endRoundApprovals()) {
                int seat = seatOf(s, approver);
                if (seat < 0 || !attackingSide(s, seat)) {
                    st.violation(run, "APPROVAL_FROM_NON_ATTACKING_SEAT", approver + " | " + compact(s));
                }
            }
            if (s.table().isEmpty()) {
                if (s.takingCardsInProgress()) {
                    st.violation(run, "TAKING_WITH_EMPTY_TABLE", compact(s));
                }
                if (!s.endRoundApprovals().isEmpty()) {
                    st.violation(run, "APPROVALS_WITH_EMPTY_TABLE", compact(s));
                }
                if (hand(s, a).isEmpty()) {
                    st.violation(run, "BOUT_ATTACKER_HAS_NO_CARDS", compact(s));
                }
                if (hand(s, d).isEmpty()) {
                    st.violation(run, "BOUT_DEFENDER_HAS_NO_CARDS", compact(s));
                }
                if (!s.talon().isEmpty()) {
                    for (int i = 0; i < n; i++) {
                        if (hand(s, i).size() < Game.MAX_HAND_SIZE) {
                            st.violation(run, "HAND_NOT_REFILLED_WHILE_TALON_REMAINS", "p" + i + " | " + compact(s));
                        }
                    }
                } else {
                    List<Integer> holders = holders(s);
                    if (holders.size() <= 1 || sameTeam(s, holders)) {
                        st.violation(run, "MISSED_FINISH", compact(s));
                    }
                }
            } else {
                long undefended = s.table().stream().filter(t -> t.defenseCard() == null).count();
                int defenderHand = hand(s, d).size();
                if (run.h0 >= 0 && s.table().size() > run.h0) {
                    st.violation(run, "ATTACKS_EXCEED_DEFENDER_HAND_AT_BOUT_START",
                            s.table().size() + " attacks vs " + run.h0 + " | " + compact(s));
                }
                if (!s.takingCardsInProgress() && undefended > defenderHand) {
                    st.violation(run, "UNDEFENDED_EXCEED_DEFENDER_HAND", compact(s));
                }
                if (s.takingCardsInProgress()) {
                    if (s.table().size() > s.takeLimit()) {
                        st.violation(run, "TABLE_EXCEEDS_TAKE_LIMIT", compact(s));
                    }
                    if (run.h0 >= 0 && s.takeLimit() != run.h0) {
                        st.violation(run, "TAKE_LIMIT_NOT_DEFENDER_BOUT_START_HAND",
                                "takeLimit=" + s.takeLimit() + " h0=" + run.h0 + " | " + compact(s));
                    }
                }
                if (s.table().size() > 6 && run.boutFlags.add("six")) {
                    st.info(run, "STD_DEVIATION: more than 6 attack cards in one bout", compact(s));
                }
                if (run.firstBout && s.table().size() > 5 && run.boutFlags.add("five")) {
                    st.info(run, "STD_DEVIATION: more than 5 attack cards in the first bout", compact(s));
                }
            }
        } else if (s.status() == GameStatus.FINISHED) {
            checkFinished(st, run, game, s);
        } else {
            st.violation(run, "UNEXPECTED_STATUS", String.valueOf(s.status()));
        }
    }

    private void checkFinished(Stats st, Run run, Game game, Game.Snapshot s) {
        if (!s.talon().isEmpty()) {
            st.violation(run, "FINISHED_WITH_TALON", compact(s));
        }
        if (!s.table().isEmpty()) {
            st.violation(run, "FINISHED_WITH_TABLE", compact(s));
        }
        List<Integer> holders = holders(s);
        if (s.loserPlayerId() == null) {
            if (!holders.isEmpty()) {
                st.violation(run, "FINISHED_WITHOUT_LOSER_BUT_CARDS_REMAIN", compact(s));
            }
        } else {
            int loser = seatOf(s, s.loserPlayerId());
            if (loser < 0) {
                st.violation(run, "LOSER_NOT_SEATED", compact(s));
            } else {
                Integer team = s.players().get(loser).team();
                if (hand(s, loser).isEmpty()) {
                    st.violation(run, "LOSER_HAS_NO_CARDS", compact(s));
                }
                if (team == null && holders.size() != 1) {
                    st.violation(run, "FINISHED_WITH_SEVERAL_CARD_HOLDERS", compact(s));
                }
                if (team != null && !holders.stream().allMatch(h -> team.equals(s.players().get(h).team()))) {
                    st.violation(run, "TEAM_FINISH_WHILE_OPPONENT_HOLDS_CARDS", compact(s));
                }
                if (!Objects.equals(s.loserPlayerName(), s.players().get(loser).name())
                        || !Objects.equals(s.loserTeam(), team)) {
                    st.violation(run, "LOSER_METADATA_MISMATCH", compact(s));
                }
            }
        }
        for (Game.PlayerSnapshot p : s.players()) {
            ViewerLegalMoves lm = game.computeViewerLegalMoves(p.id());
            if (lm.canAttack() || lm.canDefend() || lm.canTransfer() || lm.canTake() || lm.canEndRound()) {
                st.violation(run, "LEGAL_MOVES_AFTER_FINISH", p.id());
            }
        }
    }

    private void checkRealStart(Stats st, Run run, Game.Snapshot s, long versionBefore) {
        int n = s.players().size();
        if (s.status() != GameStatus.IN_PROGRESS || s.version() != versionBefore + 1) {
            st.violation(run, "START_STATUS_OR_VERSION", compact(s));
        }
        for (int i = 0; i < n; i++) {
            if (hand(s, i).size() != Game.MAX_HAND_SIZE) {
                st.violation(run, "START_HAND_NOT_SIX", compact(s));
            }
            Integer expectedTeam = n == 4 ? i % 2 : null;
            if (!Objects.equals(expectedTeam, s.players().get(i).team())) {
                st.violation(run, "START_TEAMS", compact(s));
            }
        }
        if (s.talon().size() != DECK_SIZE - Game.MAX_HAND_SIZE * n) {
            st.violation(run, "START_TALON_SIZE", compact(s));
        }
        int expectedAttacker = 0;
        int lowest = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            for (Card c : hand(s, i)) {
                if (c.suit() == s.trumpSuit() && c.rank().strength() < lowest) {
                    lowest = c.rank().strength();
                    expectedAttacker = i;
                }
            }
        }
        if (s.attackerIndex() != expectedAttacker || s.defenderIndex() != nextEligibleDefender(s, expectedAttacker)) {
            st.violation(run, "START_FIRST_ATTACKER_NOT_LOWEST_TRUMP", compact(s));
        }
    }

    private void checkAdvertisedMovesApply(Stats st, Run run, Game.Snapshot before, List<Act> moves) {
        for (Act m : moves) {
            Game copy = Game.fromSnapshot(before);
            try {
                apply(copy, m);
            } catch (RuntimeException ex) {
                st.violation(run, "ADVERTISED_MOVE_REJECTED", m + " -> " + ex + " | " + compact(before));
            }
        }
    }

    private void checkSnapshotRoundTrip(Stats st, Run run, Game.Snapshot before) {
        Game copy = Game.fromSnapshot(before);
        if (!canon(copy.toSnapshot()).equals(canon(before))) {
            st.violation(run, "SNAPSHOT_ROUNDTRIP_CHANGED_STATE", compact(before));
        }
        for (Game.PlayerSnapshot p : before.players()) {
            if (!describe(copy.computeViewerLegalMoves(p.id())).equals(describe(run.game.computeViewerLegalMoves(p.id())))) {
                st.violation(run, "SNAPSHOT_ROUNDTRIP_CHANGED_LEGAL_MOVES", p.id() + " | " + compact(before));
            }
        }
    }

    private List<Act> advertisedMoves(Stats st, Run run, Game g, Game.Snapshot s) {
        List<Act> moves = new ArrayList<>();
        for (int seat = 0; seat < s.players().size(); seat++) {
            String id = s.players().get(seat).id();
            ViewerLegalMoves lm = g.computeViewerLegalMoves(id);
            if (lm.canStart()
                    || lm.canAttack() == lm.attackableCardCodes().isEmpty()
                    || lm.canTransfer() == lm.transferableCardCodes().isEmpty()
                    || lm.canDefend() == lm.defensesByAttackCard().isEmpty()) {
                st.violation(run, "LEGAL_MOVE_FLAGS_INCONSISTENT", id + " " + describe(lm));
            }
            for (String c : lm.attackableCardCodes()) {
                moves.add(new Act(seat, id, Type.ATTACK, Card.fromCode(c), null));
            }
            for (String atk : new TreeSet<>(lm.defensesByAttackCard().keySet())) {
                for (String def : lm.defensesByAttackCard().get(atk)) {
                    moves.add(new Act(seat, id, Type.DEFEND, Card.fromCode(def), Card.fromCode(atk)));
                }
            }
            for (String c : lm.transferableCardCodes()) {
                moves.add(new Act(seat, id, Type.TRANSFER, Card.fromCode(c), null));
            }
            if (lm.canTake()) {
                moves.add(new Act(seat, id, Type.TAKE, null, null));
            }
            if (lm.canEndRound()) {
                moves.add(new Act(seat, id, Type.END_ROUND, null, null));
            }
        }
        return moves;
    }

    // ------------------------------------------------------------------ setup helpers

    /** Mirrors Game.start(): shuffle, deal 6 round-robin, trump = last remaining card, lowest trump leads. */
    static Game seededGame(int n, Random rnd) {
        List<Card> deck = new ArrayList<>(FULL_DECK);
        Collections.shuffle(deck, rnd);
        List<List<Card>> hands = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            hands.add(new ArrayList<>());
        }
        for (int round = 0; round < Game.MAX_HAND_SIZE; round++) {
            for (int i = 0; i < n; i++) {
                hands.get(i).add(deck.removeFirst());
            }
        }
        Card trump = deck.getLast();
        int attacker = 0;
        int lowest = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            for (Card c : hands.get(i)) {
                if (c.suit() == trump.suit() && c.rank().strength() < lowest) {
                    lowest = c.rank().strength();
                    attacker = i;
                }
            }
        }
        List<Game.PlayerSnapshot> players = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            players.add(new Game.PlayerSnapshot("p" + i, "P" + i, i, false, n == 4 ? i % 2 : null,
                    hands.get(i), "secret" + i));
        }
        int defender = (attacker - 1 + n) % n;
        return Game.fromSnapshot(new Game.Snapshot("FUZZ01", 0L, 0L, 0L, "p0", GameStatus.IN_PROGRESS,
                trump.suit(), trump, attacker, defender, null, false, 0, 0L, players, deck, List.of(), Set.of(),
                List.of(), List.of(), false, null, null));
    }

    static void apply(Game game, Act act) {
        switch (act.type()) {
            case ATTACK -> game.attack(act.playerId(), act.card());
            case DEFEND -> game.defend(act.playerId(), act.attackCard(), act.card());
            case TRANSFER -> game.transfer(act.playerId(), act.card());
            case TAKE -> game.takeCards(act.playerId());
            case END_ROUND -> game.endRound(act.playerId());
        }
    }

    private static Act fromAutoPlay(int seat, String id, AutoPlayAction a) {
        return switch (a.type()) {
            case ATTACK -> new Act(seat, id, Type.ATTACK, Card.fromCode(a.cardCode()), null);
            case DEFEND -> new Act(seat, id, Type.DEFEND, Card.fromCode(a.cardCode()), Card.fromCode(a.attackCardCode()));
            case TRANSFER -> new Act(seat, id, Type.TRANSFER, Card.fromCode(a.cardCode()), null);
            case TAKE -> new Act(seat, id, Type.TAKE, null, null);
            case END_ROUND -> new Act(seat, id, Type.END_ROUND, null, null);
        };
    }

    // ------------------------------------------------------------------ rule helpers (independent re-implementation)

    static boolean beats(Card attack, Card defense, Suit trump) {
        if (defense.suit() == attack.suit()) {
            return defense.rank().strength() > attack.rank().strength();
        }
        return defense.suit() == trump;
    }

    static int prev(int index, int n) {
        return (index - 1 + n) % n;
    }

    static boolean isOut(Game.Snapshot s, int seat) {
        return s.talon().isEmpty() && hand(s, seat).isEmpty();
    }

    static boolean teammate(Game.Snapshot s, int i, int j) {
        Integer ti = s.players().get(i).team();
        return ti != null && ti.equals(s.players().get(j).team());
    }

    static boolean attackingSide(Game.Snapshot s, int seat) {
        return seat != s.defenderIndex() && !teammate(s, seat, s.defenderIndex());
    }

    static int nextEligible(Game.Snapshot s, int start) {
        int n = s.players().size();
        int cur = start;
        for (int i = 0; i < n; i++) {
            cur = prev(cur, n);
            if (!isOut(s, cur)) {
                return cur;
            }
        }
        return start;
    }

    static int nextEligibleDefender(Game.Snapshot s, int attacker) {
        int n = s.players().size();
        int cur = attacker;
        for (int i = 0; i < n; i++) {
            cur = prev(cur, n);
            if (cur != attacker && !isOut(s, cur) && !teammate(s, attacker, cur)) {
                return cur;
            }
        }
        return attacker;
    }

    static List<Integer> holders(Game.Snapshot s) {
        List<Integer> holders = new ArrayList<>();
        for (int i = 0; i < s.players().size(); i++) {
            if (!hand(s, i).isEmpty()) {
                holders.add(i);
            }
        }
        return holders;
    }

    static boolean sameTeam(Game.Snapshot s, List<Integer> seats) {
        Integer team = s.players().get(seats.getFirst()).team();
        return team != null && seats.stream().allMatch(i -> team.equals(s.players().get(i).team()));
    }

    static List<Set<Card>> simulateRefill(List<List<Card>> preHands, List<Card> talon, List<Integer> order) {
        List<List<Card>> hands = new ArrayList<>();
        for (List<Card> h : preHands) {
            hands.add(new ArrayList<>(h));
        }
        List<Card> pile = new ArrayList<>(talon);
        for (int seat : order) {
            while (hands.get(seat).size() < Game.MAX_HAND_SIZE && !pile.isEmpty()) {
                hands.get(seat).add(pile.removeFirst());
            }
        }
        List<Set<Card>> out = new ArrayList<>();
        for (List<Card> h : hands) {
            out.add(new HashSet<>(h));
        }
        return out;
    }

    static int holderOf(List<Set<Card>> hands, Card card) {
        for (int i = 0; i < hands.size(); i++) {
            if (hands.get(i).contains(card)) {
                return i;
            }
        }
        return -1;
    }

    static List<Card> hand(Game.Snapshot s, int seat) {
        return s.players().get(seat).hand();
    }

    static int seatOf(Game.Snapshot s, String id) {
        for (int i = 0; i < s.players().size(); i++) {
            if (s.players().get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    static Set<Rank> tableRanks(Game.Snapshot s) {
        Set<Rank> ranks = new HashSet<>();
        for (Card c : tableCards(s)) {
            ranks.add(c.rank());
        }
        return ranks;
    }

    static List<Card> tableCards(Game.Snapshot s) {
        List<Card> cards = new ArrayList<>();
        for (Game.AttackSnapshot t : s.table()) {
            cards.add(t.attackCard());
            if (t.defenseCard() != null) {
                cards.add(t.defenseCard());
            }
        }
        return cards;
    }

    static List<Card> fullDeck() {
        List<Card> deck = new ArrayList<>();
        for (Suit suit : Suit.values()) {
            for (Rank rank : Rank.values()) {
                deck.add(new Card(rank, suit));
            }
        }
        return List.copyOf(deck);
    }

    // ------------------------------------------------------------------ formatting

    static String codes(java.util.Collection<Card> cards) {
        return cards.stream().map(Card::code).collect(Collectors.joining(" ", "[", "]"));
    }

    static String sortedCodes(java.util.Collection<Card> cards) {
        return cards.stream().map(Card::code).sorted().collect(Collectors.joining(" ", "[", "]"));
    }

    /** Canonical state per component; hands/known cards compared as multisets (order is cosmetic). */
    static Map<String, String> canon(Game.Snapshot s) {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("status", s.status() + " loser=" + s.loserPlayerId() + "/" + s.loserPlayerName() + "/" + s.loserTeam());
        c.put("trump", s.trumpSuit() + " " + (s.trumpCard() == null ? null : s.trumpCard().code()));
        c.put("roles", "a=" + s.attackerIndex() + " d=" + s.defenderIndex());
        c.put("taking", s.takingCardsInProgress() + " limit=" + s.takeLimit());
        StringBuilder hands = new StringBuilder();
        for (Game.PlayerSnapshot p : s.players()) {
            hands.append(p.id()).append(':').append(p.team()).append(sortedCodes(p.hand())).append(' ');
        }
        c.put("hands", hands.toString());
        c.put("talon", codes(s.talon()));
        c.put("table", s.table().stream()
                .map(t -> t.attackCard().code() + "/" + (t.defenseCard() == null ? "-" : t.defenseCard().code()) + "@" + t.attackerId())
                .collect(Collectors.joining(",")));
        c.put("approvals", new TreeSet<>(s.endRoundApprovals()).toString());
        c.put("discard", codes(s.discardedCards()));
        Map<String, String> known = new TreeMap<>();
        for (Game.KnownCardsSnapshot k : s.knownCardsByPlayer()) {
            known.put(k.playerId(), sortedCodes(k.cards()));
        }
        c.put("known", known.toString());
        return c;
    }

    static String diff(Game.Snapshot before, Game.Snapshot after) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < before.players().size(); i++) {
            Set<Card> b = new HashSet<>(hand(before, i));
            Set<Card> a = new HashSet<>(hand(after, i));
            Set<Card> removed = new HashSet<>(b);
            removed.removeAll(a);
            Set<Card> added = new HashSet<>(a);
            added.removeAll(b);
            if (!removed.isEmpty() || !added.isEmpty()) {
                parts.add("p" + i + " hand -" + sortedCodes(removed) + " +" + sortedCodes(added));
            }
        }
        if (!before.endRoundApprovals().equals(after.endRoundApprovals())) {
            parts.add("approvals " + new TreeSet<>(before.endRoundApprovals()) + " -> " + new TreeSet<>(after.endRoundApprovals()));
        }
        if (before.table().size() != after.table().size()) {
            parts.add("table size " + before.table().size() + " -> " + after.table().size());
        }
        int cardsBefore = countCards(before);
        int cardsAfter = countCards(after);
        if (cardsBefore != cardsAfter) {
            parts.add("TOTAL CARDS " + cardsBefore + " -> " + cardsAfter);
        }
        if (before.version() != after.version()) {
            parts.add("version " + before.version() + " -> " + after.version());
        }
        return String.join("; ", parts);
    }

    static int countCards(Game.Snapshot s) {
        int total = s.talon().size() + tableCards(s).size() + s.discardedCards().size();
        for (Game.PlayerSnapshot p : s.players()) {
            total += p.hand().size();
        }
        return total;
    }

    static String compact(Game.Snapshot s) {
        StringBuilder b = new StringBuilder();
        b.append(s.status()).append(" trump=").append(s.trumpSuit() == null ? "?" : s.trumpSuit().code())
                .append(" a=p").append(s.attackerIndex()).append(" d=p").append(s.defenderIndex());
        if (s.takingCardsInProgress()) {
            b.append(" TAKING(limit=").append(s.takeLimit()).append(')');
        }
        for (int i = 0; i < s.players().size(); i++) {
            Game.PlayerSnapshot p = s.players().get(i);
            b.append(" p").append(i).append(p.team() == null ? "" : "/t" + p.team()).append(codes(p.hand()));
        }
        b.append(" table=").append(s.table().stream()
                .map(t -> t.attackCard().code() + (t.defenseCard() == null ? "" : "/" + t.defenseCard().code()))
                .collect(Collectors.joining(",", "[", "]")));
        b.append(" talon=").append(s.talon().size() <= 12 ? codes(s.talon()) : s.talon().size() + " cards");
        b.append(" discard=").append(s.discardedCards().size());
        if (!s.endRoundApprovals().isEmpty()) {
            b.append(" approvals=").append(new TreeSet<>(s.endRoundApprovals()));
        }
        if (s.status() == GameStatus.FINISHED) {
            b.append(" loser=").append(s.loserPlayerId());
        }
        return b.toString();
    }

    static String describe(ViewerLegalMoves lm) {
        Map<String, List<String>> defenses = new TreeMap<>(lm.defensesByAttackCard());
        return "atk=" + lm.canAttack() + lm.attackableCardCodes() + " def=" + lm.canDefend() + defenses
                + " xfer=" + lm.canTransfer() + lm.transferableCardCodes() + " take=" + lm.canTake()
                + " end=" + lm.canEndRound();
    }

    // ------------------------------------------------------------------ bookkeeping

    private static final class Run {
        final String label;
        final int players;
        final Random rnd;
        Game game;
        final List<String> log = new ArrayList<>();
        String initialState = "";
        int actions;
        int h0 = -1;
        boolean firstBout = true;
        boolean tainted;
        final Set<String> boutFlags = new HashSet<>();

        Run(String label, int players, Random rnd, Game game) {
            this.label = label;
            this.players = players;
            this.rnd = rnd;
            this.game = game;
        }
    }

    private static final class Stats {
        final String title;
        long games;
        long actions;
        long probes;
        long probesRejected;
        long finished;
        long draws;
        long takes;
        long discards;
        int maxActions;
        final Map<Integer, long[]> perPlayers = new TreeMap<>();
        final Map<String, Long> violations = new TreeMap<>();
        final Map<String, String> violationExamples = new TreeMap<>();
        final Map<String, Long> infos = new TreeMap<>();
        final Map<String, String> infoExamples = new TreeMap<>();
        final long startNanos = System.nanoTime();

        Stats(String title) {
            this.title = title;
        }

        void violation(Run run, String category, String detail) {
            String cat = run.tainted ? "TAINTED/" + category : category;
            violations.merge(cat, 1L, Long::sum);
            violationExamples.computeIfAbsent(cat, k -> example(run, detail));
        }

        void info(Run run, String category, String detail) {
            infos.merge(category, 1L, Long::sum);
            infoExamples.computeIfAbsent(category, k -> example(run, detail));
        }

        private String example(Run run, String detail) {
            List<String> log = run.log;
            String actions = log.size() <= 60
                    ? String.join(" ", log)
                    : "... (" + (log.size() - 60) + " earlier) " + String.join(" ", log.subList(log.size() - 60, log.size()));
            return run.label + " step=" + log.size()
                    + "\n      detail : " + detail
                    + "\n      initial: " + run.initialState
                    + "\n      actions: " + actions;
        }

        void endGame(Run run) {
            games++;
            long[] per = perPlayers.computeIfAbsent(run.players, k -> new long[3]);
            per[0]++;
            per[1] += run.actions;
            per[2] = Math.max(per[2], run.actions);
            maxActions = Math.max(maxActions, run.actions);
            if (run.game.getStatus() == GameStatus.FINISHED) {
                finished++;
                if (run.game.getLoserPlayerId() == null) {
                    draws++;
                }
            }
        }

        String render() {
            StringBuilder b = new StringBuilder();
            b.append("==== ").append(title).append(" ====\n");
            b.append(String.format("games=%d finished=%d draws=%d actions=%d probes=%d (rejected %d) bouts: discarded=%d taken=%d maxActionsInGame=%d elapsed=%.1fs%n",
                    games, finished, draws, actions, probes, probesRejected, discards, takes, maxActions,
                    (System.nanoTime() - startNanos) / 1e9));
            perPlayers.forEach((n, per) -> b.append(String.format("  players=%d games=%d actions=%d meanActions=%.1f maxActions=%d%n",
                    n, per[0], per[1], per[0] == 0 ? 0.0 : (double) per[1] / per[0], per[2])));
            b.append("VIOLATIONS (").append(violations.size()).append(" categories)\n");
            violations.forEach((k, v) -> b.append("  [").append(v).append("x] ").append(k).append("\n    first: ")
                    .append(violationExamples.get(k)).append('\n'));
            b.append("INFO / STANDARD-RULE DEVIATIONS\n");
            infos.forEach((k, v) -> b.append("  [").append(v).append("x] ").append(k).append("\n    first: ")
                    .append(infoExamples.get(k)).append('\n'));
            return b.toString();
        }
    }

    private static void finish(Stats st, String fileName) {
        String report = st.render();
        System.out.println(report);
        try {
            Path dir = Path.of("target", "fuzz-reports");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(fileName), report);
        } catch (IOException ignored) {
            // report is also on stdout
        }
        assertTrue(st.violations.isEmpty(), "rule violations found:\n" + report);
    }
}
