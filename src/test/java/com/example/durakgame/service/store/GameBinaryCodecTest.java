package com.example.durakgame.service.store;

import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Suit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameBinaryCodecTest {
    private final GameBinaryCodec codec = new GameBinaryCodec();

    @Test
    void roundTripPreservesFullGameState() {
        Game.Snapshot original = new Game.Snapshot(
                "ROUND1",
                1_700_000_000_000L,
                "host",
                GameStatus.IN_PROGRESS,
                Suit.SPADES,
                Card.fromCode("9S"),
                0,
                2,
                null,
                true,
                4,
                17L,
                List.of(
                        new Game.PlayerSnapshot("host", "Host", 1_700_000_000_001L, false, 0, cards("6H", "AS")),
                        new Game.PlayerSnapshot("bot", "Bot Elektronik", 1_700_000_000_002L, true, 1, cards("7C")),
                        new Game.PlayerSnapshot("p3", "Third", 1_700_000_000_003L, false, 0, List.of()),
                        new Game.PlayerSnapshot("p4", "Fourth", 1_700_000_000_004L, false, 1, cards("QD", "KD", "10C"))
                ),
                cards("8D", "9S"),
                List.of(
                        new Game.AttackSnapshot(Card.fromCode("6C"), Card.fromCode("8C"), "host"),
                        new Game.AttackSnapshot(Card.fromCode("6D"), null, "p4")
                ),
                Set.of("host", "p4"),
                cards("JH", "QH"),
                List.of(new Game.KnownCardsSnapshot("bot", cards("10H", "JS")))
        );

        byte[] encoded = codec.encode(Game.fromSnapshot(original));
        assertTrue(codec.isCodecPayload(encoded));

        Game.Snapshot decoded = codec.decode(encoded).toSnapshot();
        assertEquals(original, decoded);
    }

    @Test
    void roundTripPreservesFinishedGameWithLoser() {
        Game.Snapshot original = new Game.Snapshot(
                "ROUND2",
                1_700_000_000_000L,
                "host",
                GameStatus.FINISHED,
                Suit.HEARTS,
                null,
                -1,
                -1,
                "guest",
                false,
                0,
                42L,
                List.of(
                        new Game.PlayerSnapshot("host", "Host", 1L, false, null, List.of()),
                        new Game.PlayerSnapshot("guest", "Guest", 2L, false, null, cards("6C"))
                ),
                List.of(),
                List.of(),
                Set.of(),
                List.of(),
                List.of()
        );

        Game.Snapshot decoded = codec.decode(codec.encode(Game.fromSnapshot(original))).toSnapshot();
        assertEquals(original, decoded);
    }

    @Test
    void rejectsForeignPayloads() {
        assertFalse(codec.isCodecPayload(new byte[]{1, 2, 3, 4}));
        assertThrows(IllegalStateException.class, () -> codec.decode(new byte[]{1, 2, 3, 4}));
    }

    private static List<Card> cards(String... codes) {
        return List.of(codes).stream().map(Card::fromCode).toList();
    }
}
