package com.example.durakgame.controller.dto;

import jakarta.validation.constraints.NotBlank;

/** Missing bout identity from a legacy client is a safe no-op, never a pass on a newer bout. */
public record EndRoundRequest(@NotBlank String playerId, String boutId) {
}
