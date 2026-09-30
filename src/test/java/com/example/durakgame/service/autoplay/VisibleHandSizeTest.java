package com.example.durakgame.service.autoplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VisibleHandSizeTest {

    @Test
    void showsExactCountsBelowSixAndSixPlusOtherwise() {
        assertEquals(0, VisibleHandSize.describe(0));
        assertEquals(5, VisibleHandSize.describe(5));
        assertEquals("6+", VisibleHandSize.describe(6));
        assertEquals("6+", VisibleHandSize.describe(14));
    }

    @Test
    void lowerBoundIsWhatAPlayerCanBeSureOf() {
        assertEquals(4, VisibleHandSize.lowerBound(4));
        assertEquals(6, VisibleHandSize.lowerBound(6));
        assertEquals(6, VisibleHandSize.lowerBound(11));
    }
}
