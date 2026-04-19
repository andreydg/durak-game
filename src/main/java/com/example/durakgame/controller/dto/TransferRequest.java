package com.example.durakgame.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record TransferRequest(
        @NotBlank String playerId,
        @NotBlank String card
) {
}
