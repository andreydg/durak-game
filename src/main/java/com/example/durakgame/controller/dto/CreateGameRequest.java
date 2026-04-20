package com.example.durakgame.controller.dto;

import jakarta.validation.constraints.Size;

/** {@code hostName} optional — blank means server assigns a guest name. */
public record CreateGameRequest(
        @Size(max = 24) String hostName
) {
}
