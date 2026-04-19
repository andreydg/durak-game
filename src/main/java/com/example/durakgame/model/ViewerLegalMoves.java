package com.example.durakgame.model;

import java.util.List;
import java.util.Map;

/**
 * Legal actions for a viewer, computed from the same rules as {@link Game} mutations.
 */
public record ViewerLegalMoves(
        boolean canStart,
        boolean canAttack,
        boolean canDefend,
        boolean canTransfer,
        boolean canTake,
        boolean canEndRound,
        List<String> attackableCardCodes,
        List<String> transferableCardCodes,
        Map<String, List<String>> defensesByAttackCard
) {
    public static ViewerLegalMoves empty() {
        return new ViewerLegalMoves(
                false,
                false,
                false,
                false,
                false,
                false,
                List.of(),
                List.of(),
                Map.of()
        );
    }
}
