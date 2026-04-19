package com.example.durakgame.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinGameRequest(
        @NotBlank @Size(min = 2, max = 24) String playerName
) {
}
