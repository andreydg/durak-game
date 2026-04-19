package com.example.durakgame.controller.dto;

public record CreateGameResponse(
        GameResponse game,
        String hostPlayerId
) {
}
