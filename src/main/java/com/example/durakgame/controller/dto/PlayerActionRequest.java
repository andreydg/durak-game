package com.example.durakgame.controller.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code token} is an optional fallback for the leave-beacon, which cannot set request headers. */
public record PlayerActionRequest(
        @NotBlank String playerId,
        String token
) {
}
