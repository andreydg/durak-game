package com.example.durakgame.controller.dto;

public record LobbyGameSummary(
        String code,
        String hostName,
        int playerCount,
        int maxPlayers
) {}
