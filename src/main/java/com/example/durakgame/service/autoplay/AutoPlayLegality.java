package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.Card;
import com.example.durakgame.model.ViewerLegalMoves;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Legality checks and option enumeration shared by the auto-play engines. Every check is null-safe:
 * {@link ViewerLegalMoves} holds immutable collections, whose {@code contains(null)} throws.
 */
final class AutoPlayLegality {
    private AutoPlayLegality() {
    }

    static boolean isLegal(AutoPlayAction action, ViewerLegalMoves moves) {
        if (action == null || action.type() == null || moves == null) {
            return false;
        }
        String card = action.cardCode();
        return switch (action.type()) {
            case ATTACK -> moves.canAttack() && card != null && moves.attackableCardCodes().contains(card);
            case DEFEND -> moves.canDefend() && card != null && action.attackCardCode() != null
                    && defencesFor(moves, action.attackCardCode()).contains(card);
            case TRANSFER -> moves.canTransfer() && card != null && moves.transferableCardCodes().contains(card);
            case TAKE -> moves.canTake();
            case END_ROUND -> moves.canEndRound();
        };
    }

    static List<String> defencesFor(ViewerLegalMoves moves, String attackCardCode) {
        if (attackCardCode == null) {
            return List.of();
        }
        List<String> defences = moves.defensesByAttackCard().get(attackCardCode);
        return defences == null ? List.of() : defences;
    }

    /**
     * Number of distinct legal actions: each attack card, each (attack, defence) pair, each transfer
     * card, plus take and end-round. "Waiting" is not counted.
     */
    static int optionCount(ViewerLegalMoves moves) {
        return enumerate(moves).size();
    }

    /** Every legal action in a deterministic order. */
    static List<AutoPlayAction> enumerate(ViewerLegalMoves moves) {
        List<AutoPlayAction> options = new ArrayList<>();
        if (moves == null) {
            return options;
        }
        if (moves.canAttack()) {
            moves.attackableCardCodes().forEach(code -> options.add(AutoPlayAction.attack(code)));
        }
        if (moves.canDefend()) {
            new TreeMap<>(moves.defensesByAttackCard()).forEach((attack, defences) ->
                    defences.forEach(defence -> options.add(AutoPlayAction.defend(attack, defence))));
        }
        if (moves.canTransfer()) {
            moves.transferableCardCodes().forEach(code -> options.add(AutoPlayAction.transfer(code)));
        }
        if (moves.canTake()) {
            options.add(AutoPlayAction.take());
        }
        if (moves.canEndRound()) {
            options.add(AutoPlayAction.endRound());
        }
        return options;
    }

    /**
     * Canonical card code ("10D", "QS") for loosely formatted model output such as {@code " 6c"},
     * {@code "td"} or {@code "10♦"}; {@code null} when the text is not a card.
     */
    static String canonicalCardCode(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim().toUpperCase(Locale.ROOT);
        for (Map.Entry<String, String> glyph : SUIT_GLYPHS.entrySet()) {
            text = text.replace(glyph.getKey(), glyph.getValue());
        }
        if (text.length() == 2 && text.charAt(0) == 'T') {
            text = "10" + text.charAt(1);
        }
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Card.fromCode(text).code();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static final Map<String, String> SUIT_GLYPHS = Map.of(
            "♣", "C", "♧", "C",
            "♦", "D", "♢", "D",
            "♥", "H", "♡", "H",
            "♠", "S", "♤", "S"
    );
}
