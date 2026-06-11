package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.Suit;
import com.example.durakgame.model.ViewerLegalMoves;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class HeuristicAutoPlayDecisionEngine implements AutoPlayDecisionEngine {
    @Override
    public AutoPlayAction choose(Game game, String playerId, ViewerLegalMoves legalMoves) {
        Comparator<String> cheapestFirst = cheapestFirst(game.getTrumpSuit());
        if (legalMoves.canDefend()) {
            Map<String, List<String>> byAttack = legalMoves.defensesByAttackCard();
            return byAttack.entrySet().stream()
                    .min(Comparator
                            .comparingInt((Map.Entry<String, List<String>> entry) -> entry.getValue().size())
                            .thenComparing(Map.Entry::getKey))
                    .flatMap(entry -> entry.getValue().stream().min(cheapestFirst)
                            .map(defense -> AutoPlayAction.defend(entry.getKey(), defense)))
                    .orElse(null);
        }
        if (legalMoves.canTransfer() && !legalMoves.transferableCardCodes().isEmpty()) {
            return AutoPlayAction.transfer(legalMoves.transferableCardCodes().stream().min(cheapestFirst).orElse(null));
        }
        if (legalMoves.canAttack() && !legalMoves.attackableCardCodes().isEmpty()) {
            return AutoPlayAction.attack(legalMoves.attackableCardCodes().stream().min(cheapestFirst).orElse(null));
        }
        if (legalMoves.canTake()) {
            return AutoPlayAction.take();
        }
        if (legalMoves.canEndRound()) {
            return AutoPlayAction.endRound();
        }
        return null;
    }

    /** Spend non-trumps before trumps, low ranks before high (real card value, not code text). */
    static Comparator<String> cheapestFirst(Suit trumpSuit) {
        return Comparator
                .comparing((String code) -> Card.fromCode(code).suit() == trumpSuit)
                .thenComparingInt(code -> Card.fromCode(code).rank().strength())
                .thenComparing(Comparator.naturalOrder());
    }
}
