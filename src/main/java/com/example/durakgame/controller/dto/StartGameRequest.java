package com.example.durakgame.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record StartGameRequest(
        @NotBlank String playerId
) {
}
