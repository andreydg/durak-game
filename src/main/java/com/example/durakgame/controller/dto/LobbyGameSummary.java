package com.example.durakgame.controller.dto;

public record LobbyGameSummary(
        String code,
        String hostName,
        java.util.List<String> playerNames,
        int playerCount,
        int maxPlayers,
        java.time.Instant lastActivityAt,
        java.time.Instant expiresAt
) {}
