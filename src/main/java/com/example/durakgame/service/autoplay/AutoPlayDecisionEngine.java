package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.ViewerLegalMoves;

public interface AutoPlayDecisionEngine {
    AutoPlayAction choose(Game game, String playerId, ViewerLegalMoves legalMoves);
}
