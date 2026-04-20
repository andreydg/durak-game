package com.example.durakgame.controller.dto;

import jakarta.validation.constraints.Size;

/** {@code playerName} optional — blank means server assigns a guest name. */
public record JoinGameRequest(
        @Size(max = 24) String playerName
) {
}
