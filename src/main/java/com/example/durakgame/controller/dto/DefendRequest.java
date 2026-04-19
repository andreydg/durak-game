package com.example.durakgame.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record DefendRequest(
        @NotBlank String playerId,
        @NotBlank String attackCard,
        @NotBlank String defenseCard
) {
}
