package com.example.durakgame.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record PlayerActionRequest(
        @NotBlank String playerId
) {
}
