package com.example.durakgame.model;

public enum Suit {
    CLUBS("C"),
    DIAMONDS("D"),
    HEARTS("H"),
    SPADES("S");

    private final String code;

    Suit(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Suit fromCode(String code) {
        for (Suit suit : values()) {
            if (suit.code.equalsIgnoreCase(code)) {
                return suit;
            }
        }
        throw new IllegalArgumentException("Unknown suit: " + code);
    }
}
