package com.example.durakgame.service.autoplay;

/**
 * How many cards another seat visibly holds. The table draws at most six card backs per opponent
 * (screen readers hear "6 or more cards"), so bots get the same view as human players: the exact
 * count below six, otherwise only that it is six or more.
 */
final class VisibleHandSize {
    static final int CAP = 6;

    private VisibleHandSize() {
    }

    /** Prompt value: the exact count below the cap, otherwise {@code "6+"}. */
    static Object describe(int handSize) {
        return handSize < CAP ? (Object) handSize : CAP + "+";
    }

    /** The count a player can be sure of: exact below the cap, otherwise the cap itself. */
    static int lowerBound(int handSize) {
        return Math.min(handSize, CAP);
    }
}
