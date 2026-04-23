package com.example.durakgame.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddBotRequest(
        @NotBlank String playerId,
        @Size(max = 24) String botName
) {
}
