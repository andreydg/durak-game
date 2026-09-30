package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.AttackEntry;
import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.Player;
import com.example.durakgame.model.Rank;
import com.example.durakgame.model.Suit;
import com.example.durakgame.model.ViewerLegalMoves;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic bot used when the LLM is disabled, unavailable, or answers badly.
 *
 * <p>It decides only from what a human in the bot's seat can see: its own hand, the table, the
 * trump, whether the talon still has cards / only the face-up trump / nothing, the number of
 * completed bouts, public hand sizes, and cards that publicly left the game. It never reads other
 * players' hands or the talon order.
 *
 * <p>Card economy in brief: low non-trumps are junk worth shedding, trumps are precious while the
 * talon still refills hands, and once the talon is empty the only goal is to run out of cards.
 * Defending, transferring and taking are compared by an estimated cost that depends on that phase.
 * The thresholds were tuned in simulated games against the previous fixed-priority policy, which
 * this version beats in two-, three- and four-player (team) games.
 */
@Component
public class HeuristicAutoPlayDecisionEngine implements AutoPlayDecisionEngine {
    private static final Logger log = LoggerFactory.getLogger(HeuristicAutoPlayDecisionEngine.class);

    /** Cost assigned to a move that empties the bot's hand once the talon is gone: always preferred. */
    private static final double GOING_OUT = -1_000.0;
    /** Transferring sheds a card and hands every attack on the table to the next defender. */
    private static final double TRANSFER_PRESSURE_BONUS = 0.5;
    /** Safety valve for the defence-assignment search on pathological (huge) tables. */
    private static final int MAX_SEARCH_NODES = 250_000;

    @Override
    public AutoPlayAction choose(Game game, String playerId, ViewerLegalMoves legalMoves) {
        if (legalMoves == null) {
            return null;
        }
        try {
            AutoPlayAction action = new Decision(game, playerId, legalMoves).choose();
            if (action == null || AutoPlayLegality.isLegal(action, legalMoves)) {
                return action;
            }
            log.warn("autoplay_heuristic_illegal code={} player={} action={}", codeOf(game), playerId, action);
        } catch (RuntimeException ex) {
            log.warn("autoplay_heuristic_failed code={} player={} error={}", codeOf(game), playerId, ex.toString());
        }
        return simpleChoice(game == null ? null : game.getTrumpSuit(), legalMoves);
    }

    /** Spend non-trumps before trumps, low ranks before high (real card value, not code text). */
    static Comparator<String> cheapestFirst(Suit trumpSuit) {
        return Comparator
                .comparing((String code) -> Card.fromCode(code).suit() == trumpSuit)
                .thenComparingInt(code -> Card.fromCode(code).rank().strength())
                .thenComparing(Comparator.naturalOrder());
    }

    /** The original fixed-priority policy; only used if the strategic decision fails unexpectedly. */
    private static AutoPlayAction simpleChoice(Suit trumpSuit, ViewerLegalMoves legalMoves) {
        Comparator<String> cheapestFirst = cheapestFirst(trumpSuit);
        if (legalMoves.canDefend()) {
            return legalMoves.defensesByAttackCard().entrySet().stream()
                    .min(Comparator
                            .comparingInt((Map.Entry<String, List<String>> entry) -> entry.getValue().size())
                            .thenComparing(Map.Entry::getKey))
                    .flatMap(entry -> entry.getValue().stream().min(cheapestFirst)
                            .map(defense -> AutoPlayAction.defend(entry.getKey(), defense)))
                    .orElse(null);
        }
        if (legalMoves.canTransfer() && !legalMoves.transferableCardCodes().isEmpty()) {
            return AutoPlayAction.transfer(legalMoves.transferableCardCodes().stream().min(cheapestFirst).orElseThrow());
        }
        if (legalMoves.canAttack() && !legalMoves.attackableCardCodes().isEmpty()) {
            return AutoPlayAction.attack(legalMoves.attackableCardCodes().stream().min(cheapestFirst).orElseThrow());
        }
        if (legalMoves.canTake()) {
            return AutoPlayAction.take();
        }
        if (legalMoves.canEndRound()) {
            return AutoPlayAction.endRound();
        }
        return null;
    }

    private static String codeOf(Game game) {
        return game == null ? null : game.getCode();
    }

    /**
     * Game phase as a human sees it: the talon's visible state (cards left / only the face-up trump /
     * empty) refined by how many bouts have been played, never by the exact talon count.
     */
    enum Phase {
        /** Plenty of cards still to draw: conserve trumps and high cards, shed junk. */
        OPENING,
        /** The talon is running out (or only the trump card is left): trumps lose some premium. */
        MIDGAME,
        /** Nothing left to draw: whoever runs out of cards first is safe. */
        ENDGAME;

        static Phase of(int talonSize, int boutsCompleted, int seatedPlayers) {
            if (talonSize <= 0) {
                return ENDGAME;
            }
            if (talonSize == 1) {
                return MIDGAME;
            }
            /* Fewer players draw fewer cards per bout, so the talon lasts more bouts. */
            int midgameAfterBouts = seatedPlayers <= 2 ? 5 : (seatedPlayers == 3 ? 3 : 2);
            return boutsCompleted >= midgameAfterBouts ? MIDGAME : OPENING;
        }
    }

    /** One decision for one seat; holds the visible state and the phase-dependent card economy. */
    private static final class Decision {
        private final Game game;
        private final ViewerLegalMoves moves;
        private final Suit trump;
        private final Phase phase;
        private final List<Card> hand;
        private final List<AttackEntry> table;
        private final boolean taking;
        private final int defenderHandSize;
        private final Comparator<Card> cardOrder;
        private Set<Card> unseen;

        Decision(Game game, String playerId, ViewerLegalMoves moves) {
            this.game = game;
            this.moves = moves;
            this.trump = game.getTrumpSuit();
            List<Player> players = game.getPlayers();
            this.hand = players.stream()
                    .filter(player -> Objects.equals(player.getId(), playerId))
                    .findFirst()
                    .map(Player::getHand)
                    .orElse(List.of());
            this.table = game.getTable();
            this.taking = game.isTakingCardsInProgress();
            this.defenderHandSize = players.stream()
                    .filter(player -> Objects.equals(player.getId(), game.getDefenderPlayerId()))
                    .findFirst()
                    .map(Player::handSize)
                    .orElse(0);
            this.phase = Phase.of(game.getTalonSize(), game.getBoutsCompleted(), players.size());
            this.cardOrder = Comparator
                    .comparing((Card card) -> isTrump(card))
                    .thenComparingInt(card -> card.rank().strength())
                    .thenComparing(card -> card.suit().ordinal());
        }

        AutoPlayAction choose() {
            if (moves.canDefend() || moves.canTransfer()) {
                return respondToAttack();
            }
            if (moves.canTake()) {
                return AutoPlayAction.take();
            }
            if (moves.canAttack()) {
                return attackOrPass();
            }
            if (moves.canEndRound()) {
                return AutoPlayAction.endRound();
            }
            return null;
        }

        /* ---------------------------------------------------------------- defending */

        /** Compares the cheapest complete defence, the cheapest transfer and taking the table. */
        private AutoPlayAction respondToAttack() {
            List<Card> undefended = undefendedAttacks();
            DefencePlan plan = moves.canDefend() ? cheapestFullDefence(undefended) : null;
            Card transferCard = moves.canTransfer()
                    ? cards(moves.transferableCardCodes()).stream()
                    .min(Comparator.comparingDouble(this::spendCost).thenComparing(cardOrder))
                    .orElse(null)
                    : null;

            double defendCost = plan == null ? Double.POSITIVE_INFINITY : plan.cost();
            if (plan != null && phase == Phase.ENDGAME && plan.size() >= hand.size()) {
                defendCost = GOING_OUT;
            }
            double transferCost = transferCard == null ? Double.POSITIVE_INFINITY : transferCost(transferCard);
            double takeCost = moves.canTake() ? takeCost(undefended) : Double.POSITIVE_INFINITY;

            if (transferCard != null && transferCost <= defendCost && transferCost <= takeCost) {
                return AutoPlayAction.transfer(transferCard.code());
            }
            if (plan != null && defendCost <= takeCost) {
                return plan.firstMove();
            }
            if (moves.canTake()) {
                /* Also covers "some attack cannot be beaten at all": a partial defence only wastes cards. */
                return AutoPlayAction.take();
            }
            if (plan != null) {
                return plan.firstMove();
            }
            return transferCard == null ? null : AutoPlayAction.transfer(transferCard.code());
        }

        private double transferCost(Card card) {
            if (phase == Phase.ENDGAME && hand.size() == 1) {
                return GOING_OUT;
            }
            return spendCost(card) - TRANSFER_PRESSURE_BONUS;
        }

        /** Losing the lead plus the value of every card that would join the hand. */
        private double takeCost(List<Card> undefended) {
            List<Card> pile = tableCards();
            if (pile.isEmpty()) {
                pile = undefended;
            }
            double cost = tempoCost();
            for (Card card : pile) {
                cost += pickupCost(card);
            }
            return cost;
        }

        private List<Card> undefendedAttacks() {
            Set<Card> attacks = new LinkedHashSet<>();
            for (AttackEntry entry : table) {
                if (!entry.isDefended()) {
                    attacks.add(entry.getAttackCard());
                }
            }
            /* Legal moves are authoritative for which attacks can be answered right now. */
            attacks.addAll(cards(List.copyOf(new TreeSet<>(moves.defensesByAttackCard().keySet()))));
            return List.copyOf(attacks);
        }

        /**
         * Minimum-cost assignment of distinct defence cards to every undefended attack, or
         * {@code null} when some attack cannot be beaten. Branch and bound over the attacks, most
         * constrained first, using each attack's cheapest option as the lower bound.
         */
        private DefencePlan cheapestFullDefence(List<Card> undefended) {
            if (undefended.isEmpty()) {
                return null;
            }
            Map<Card, List<Card>> options = new HashMap<>();
            for (Card attack : undefended) {
                List<Card> defences = new ArrayList<>(cards(AutoPlayLegality.defencesFor(moves, attack.code())));
                if (defences.isEmpty()) {
                    return null;
                }
                defences.sort(Comparator.comparingDouble(this::spendCost).thenComparing(cardOrder));
                options.put(attack, defences);
            }
            List<Card> attacks = new ArrayList<>(undefended);
            attacks.sort(Comparator.comparingInt((Card attack) -> options.get(attack).size())
                    .thenComparing(cardOrder));
            AssignmentSearch search = new AssignmentSearch(attacks, options);
            search.run(0, 0.0);
            if (search.best == null) {
                return null;
            }
            return new DefencePlan(attacks, List.of(search.best), search.bestCost);
        }

        private final class AssignmentSearch {
            private final List<Card> attacks;
            private final List<List<Card>> options;
            private final double[] lowerBoundFrom;
            private final Card[] chosen;
            private final Set<Card> used = new HashSet<>();
            private Card[] best;
            private double bestCost = Double.POSITIVE_INFINITY;
            private int nodes;

            AssignmentSearch(List<Card> attacks, Map<Card, List<Card>> optionsByAttack) {
                this.attacks = attacks;
                this.options = attacks.stream().map(optionsByAttack::get).toList();
                this.lowerBoundFrom = new double[attacks.size() + 1];
                for (int i = attacks.size() - 1; i >= 0; i--) {
                    lowerBoundFrom[i] = lowerBoundFrom[i + 1] + spendCost(options.get(i).getFirst());
                }
                this.chosen = new Card[attacks.size()];
            }

            void run(int index, double cost) {
                if (++nodes > MAX_SEARCH_NODES || cost + lowerBoundFrom[index] >= bestCost) {
                    return;
                }
                if (index == attacks.size()) {
                    best = chosen.clone();
                    bestCost = cost;
                    return;
                }
                for (Card defence : options.get(index)) {
                    if (used.add(defence)) {
                        chosen[index] = defence;
                        run(index + 1, cost + spendCost(defence));
                        used.remove(defence);
                    }
                }
            }
        }

        /* ---------------------------------------------------------------- attacking */

        private AutoPlayAction attackOrPass() {
            List<Card> candidates = cards(moves.attackableCardCodes());
            if (candidates.isEmpty()) {
                return moves.canEndRound() ? AutoPlayAction.endRound() : null;
            }
            if (table.isEmpty() && !taking) {
                /* Opening a bout is mandatory. */
                return AutoPlayAction.attack(chooseLead(candidates).code());
            }
            Card throwIn = chooseThrowIn(candidates);
            if (throwIn != null) {
                return AutoPlayAction.attack(throwIn.code());
            }
            /* Pass. Without END_ROUND (already passed) the bot simply waits for the others. */
            return moves.canEndRound() ? AutoPlayAction.endRound() : null;
        }

        private Card chooseLead(List<Card> candidates) {
            List<Card> nonTrumps = candidates.stream().filter(card -> !isTrump(card)).toList();
            if (phase == Phase.ENDGAME) {
                /* Card counting: a card nobody can beat forces the defender to take. */
                Card forcing = nonTrumps.stream().filter(this::unbeatable).min(cardOrder).orElse(null);
                if (forcing != null) {
                    return forcing;
                }
            }
            if (!nonTrumps.isEmpty()) {
                return nonTrumps.stream()
                        .min(Comparator.comparingDouble((Card card) -> leadScore(card, nonTrumps))
                                .thenComparing(cardOrder))
                        .orElseThrow();
            }
            return candidates.stream().min(cardOrder).orElseThrow();
        }

        /** Low cards first; a rank we hold several of can be thrown in again, shedding more. */
        private double leadScore(Card card, List<Card> nonTrumps) {
            long sameRank = nonTrumps.stream()
                    .filter(other -> other.rank() == card.rank() && !other.equals(card))
                    .count();
            double setBonus = phase == Phase.ENDGAME ? 3.0 : 1.5;
            return card.rank().strength() - setBonus * sameRank;
        }

        private Card chooseThrowIn(List<Card> candidates) {
            boolean goingOut = canGoOutByThrowingIn(candidates);
            return candidates.stream()
                    .filter(card -> goingOut || worthThrowingIn(card))
                    .min(Comparator.comparingDouble(this::spendCost).thenComparing(cardOrder))
                    .orElse(null);
        }

        /**
         * Optional throw-in policy. A defender who is taking keeps whatever is thrown, so only junk
         * goes to them. Against a defender who is still beating cards, pressure pays off, but while
         * the talon still refills hands trumps and aces are worth more kept than spent. Once nothing
         * refills, every card thrown in is one fewer to shed, so everything goes.
         */
        private boolean worthThrowingIn(Card card) {
            int strength = card.rank().strength();
            if (taking) {
                return !isTrump(card)
                        && strength <= (phase == Phase.ENDGAME ? Rank.KING.strength() : Rank.NINE.strength());
            }
            return switch (phase) {
                case OPENING -> !isTrump(card) && strength <= Rank.QUEEN.strength();
                case MIDGAME -> !isTrump(card) && strength <= Rank.KING.strength();
                case ENDGAME -> true;
            };
        }

        /** With the talon empty, throwing in every remaining card ends the game for this bot. */
        private boolean canGoOutByThrowingIn(List<Card> candidates) {
            if (phase != Phase.ENDGAME || hand.isEmpty() || !new HashSet<>(candidates).containsAll(hand)) {
                return false;
            }
            long undefended = table.stream().filter(entry -> !entry.isDefended()).count();
            int capacity = taking
                    ? game.getTakeLimit() - table.size()
                    : defenderHandSize - (int) undefended;
            return capacity >= hand.size();
        }

        /* ---------------------------------------------------------------- card economy */

        /** Strategic value lost by playing this card from the hand. */
        private double spendCost(Card card) {
            double rankFactor = rankFactor(card);
            if (!isTrump(card)) {
                return rankFactor;
            }
            return switch (phase) {
                case OPENING -> 2.0 + 2.0 * rankFactor;
                case MIDGAME -> 1.5 + 1.5 * rankFactor;
                case ENDGAME -> 1.0 + rankFactor;
            };
        }

        /** Cost of adding this card to the hand by taking: junk hurts, trumps and aces help. */
        private double pickupCost(Card card) {
            double rankFactor = rankFactor(card);
            double quality = isTrump(card) ? -(0.5 + 0.5 * rankFactor) : 1.0 - rankFactor;
            double perCard = switch (phase) {
                case OPENING -> 0.5;
                case MIDGAME -> 1.0;
                case ENDGAME -> 2.0;
            };
            double crowding = 0.1 * Math.max(0, hand.size() - Game.MAX_HAND_SIZE);
            return quality + perCard + crowding;
        }

        /** Taking also hands the next lead to someone else. */
        private double tempoCost() {
            return switch (phase) {
                case OPENING -> 1.0;
                case MIDGAME -> 1.5;
                case ENDGAME -> 3.0;
            };
        }

        private static double rankFactor(Card card) {
            return (card.rank().strength() - Rank.SIX.strength()) / 8.0;
        }

        private boolean isTrump(Card card) {
            return card.suit() == trump;
        }

        /** True when no card this seat has not seen (in hand, on the table or discarded) can beat it. */
        private boolean unbeatable(Card card) {
            for (Card other : unseen()) {
                if (beats(other, card)) {
                    return false;
                }
            }
            return true;
        }

        private boolean beats(Card defence, Card attack) {
            if (defence.suit() == attack.suit()) {
                return defence.rank().strength() > attack.rank().strength();
            }
            return defence.suit() == trump && attack.suit() != trump;
        }

        private Set<Card> unseen() {
            if (unseen == null) {
                Set<Card> cards = new HashSet<>();
                for (Suit suit : Suit.values()) {
                    for (Rank rank : Rank.values()) {
                        cards.add(new Card(rank, suit));
                    }
                }
                hand.forEach(cards::remove);
                game.getDiscardedCards().forEach(cards::remove);
                tableCards().forEach(cards::remove);
                unseen = cards;
            }
            return unseen;
        }

        private List<Card> tableCards() {
            List<Card> cards = new ArrayList<>();
            for (AttackEntry entry : table) {
                cards.add(entry.getAttackCard());
                if (entry.getDefenseCard() != null) {
                    cards.add(entry.getDefenseCard());
                }
            }
            return cards;
        }

        private static List<Card> cards(List<String> codes) {
            List<Card> cards = new ArrayList<>(codes.size());
            for (String code : codes) {
                String canonical = AutoPlayLegality.canonicalCardCode(code);
                if (canonical != null) {
                    cards.add(Card.fromCode(canonical));
                }
            }
            return cards;
        }
    }

    /** Cheapest full defence: {@code attacks.get(i)} is beaten by {@code defences.get(i)}. */
    private record DefencePlan(List<Card> attacks, List<Card> defences, double cost) {
        int size() {
            return attacks.size();
        }

        /** Most constrained attack first, so flexible cards stay available for later throw-ins. */
        AutoPlayAction firstMove() {
            return AutoPlayAction.defend(attacks.getFirst().code(), defences.getFirst().code());
        }
    }
}
