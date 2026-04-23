package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.ViewerLegalMoves;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class HeuristicAutoPlayDecisionEngine implements AutoPlayDecisionEngine {
    @Override
    public AutoPlayAction choose(Game game, String playerId, ViewerLegalMoves legalMoves) {
        if (legalMoves.canDefend()) {
            Map<String, List<String>> byAttack = legalMoves.defensesByAttackCard();
            return byAttack.entrySet().stream()
                    .min(Comparator.comparing(Map.Entry::getKey))
                    .flatMap(entry -> entry.getValue().stream().min(String::compareTo)
                            .map(defense -> AutoPlayAction.defend(entry.getKey(), defense)))
                    .orElse(null);
        }
        if (legalMoves.canTransfer() && !legalMoves.transferableCardCodes().isEmpty()) {
            return AutoPlayAction.transfer(legalMoves.transferableCardCodes().stream().min(String::compareTo).orElse(null));
        }
        if (legalMoves.canAttack() && !legalMoves.attackableCardCodes().isEmpty()) {
            return AutoPlayAction.attack(legalMoves.attackableCardCodes().stream().min(String::compareTo).orElse(null));
        }
        if (legalMoves.canTake()) {
            return AutoPlayAction.take();
        }
        if (legalMoves.canEndRound()) {
            return AutoPlayAction.endRound();
        }
        return null;
    }
}
