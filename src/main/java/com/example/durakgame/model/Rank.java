package com.example.durakgame.model;

public enum Rank {
    SIX("6", 6),
    SEVEN("7", 7),
    EIGHT("8", 8),
    NINE("9", 9),
    TEN("10", 10),
    JACK("J", 11),
    QUEEN("Q", 12),
    KING("K", 13),
    ACE("A", 14);

    private final String code;
    private final int strength;

    Rank(String code, int strength) {
        this.code = code;
        this.strength = strength;
    }

    public String code() {
        return code;
    }

    public int strength() {
        return strength;
    }

    public static Rank fromCode(String code) {
        for (Rank rank : values()) {
            if (rank.code.equalsIgnoreCase(code)) {
                return rank;
            }
        }
        throw new IllegalArgumentException("Unknown rank: " + code);
    }
}
