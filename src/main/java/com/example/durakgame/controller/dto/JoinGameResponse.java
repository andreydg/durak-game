package com.example.durakgame.controller.dto;

public record JoinGameResponse(
        GameResponse game,
        String playerId
) {
}
