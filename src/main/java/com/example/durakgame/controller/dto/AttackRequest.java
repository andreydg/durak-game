package com.example.durakgame.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record AttackRequest(
        @NotBlank String playerId,
        @NotBlank String card
) {
}
