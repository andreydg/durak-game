package com.example.durakgame.model;

import java.io.Serializable;
import java.util.Locale;

public record Card(Rank rank, Suit suit) implements Serializable {
    public String code() {
        return rank.code() + suit.code();
    }

    public static Card fromCode(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Card code is required");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() < 2 || normalized.length() > 3) {
            throw new IllegalArgumentException("Invalid card code: " + raw);
        }

        String suitCode = normalized.substring(normalized.length() - 1);
        String rankCode = normalized.substring(0, normalized.length() - 1);
        return new Card(Rank.fromCode(rankCode), Suit.fromCode(suitCode));
    }
}
