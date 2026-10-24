package com.example.durakgame.controller.dto;

import jakarta.validation.constraints.NotBlank;

/** Missing/blank identity gets a reload-required conflict; stale identities are safe no-ops. */
public record EndRoundRequest(@NotBlank String playerId, String boutId) {
}
