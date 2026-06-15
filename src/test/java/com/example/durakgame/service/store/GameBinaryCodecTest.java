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
                        new Game.PlayerSnapshot("host", "Host", 1_700_000_000_001L, false, 0, cards("6H", "AS"), "sec-host"),
                        new Game.PlayerSnapshot("bot", "Bot Elektronik", 1_700_000_000_002L, true, 1, cards("7C"), "sec-bot"),
                        new Game.PlayerSnapshot("p3", "Third", 1_700_000_000_003L, false, 0, List.of(), "sec-p3"),
                        new Game.PlayerSnapshot("p4", "Fourth", 1_700_000_000_004L, false, 1, cards("QD", "KD", "10C"), "sec-p4")
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
                        new Game.PlayerSnapshot("host", "Host", 1L, false, null, List.of(), "sec-host"),
                        new Game.PlayerSnapshot("guest", "Guest", 2L, false, null, cards("6C"), "sec-guest")
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

    @Test
    void decodesLegacyV3PayloadWithBlankSecret() throws Exception {
        // A version-3 payload (pre-token) must still decode after the v4 bump, not be destroyed.
        byte[] legacy = encodeV3SinglePlayerLobby("LEGCY1", "host", "Host");

        Game decoded = codec.decode(legacy);

        assertEquals("LEGCY1", decoded.getCode());
        assertEquals(GameStatus.LOBBY, decoded.getStatus());
        assertEquals(1, decoded.getPlayers().size());
        assertEquals("", decoded.getPlayers().getFirst().getSecret());
    }

    /** Hand-writes a minimal version-3 lobby payload (the format before the player-secret field). */
    private static byte[] encodeV3SinglePlayerLobby(String code, String playerId, String name) throws Exception {
        var bos = new java.io.ByteArrayOutputStream();
        try (var out = new java.io.DataOutputStream(bos)) {
            out.write(new byte[]{'D', 'G', '1'});
            out.writeByte(3);                       // version
            out.writeUTF(code);
            out.writeLong(1_700_000_000_000L);      // createdAt
            out.writeUTF(playerId);                 // hostPlayerId
            out.writeByte(GameStatus.LOBBY.ordinal());
            out.writeBoolean(false);                // trumpSuit absent
            out.writeBoolean(false);                // trumpCard absent
            out.writeInt(-1);                       // attackerIndex
            out.writeInt(-1);                       // defenderIndex
            out.writeBoolean(false);                // loserPlayerId absent
            out.writeBoolean(false);                // takingCardsInProgress
            out.writeInt(0);                        // takeLimit
            out.writeLong(0L);                      // version
            out.writeInt(1);                        // playerCount
            out.writeUTF(playerId);
            out.writeUTF(name);
            out.writeLong(1_700_000_000_001L);      // joinedAt
            out.writeBoolean(false);                // bot
            // (no secret field in v3)
            out.writeBoolean(false);                // team absent
            out.writeInt(0);                        // hand size
            out.writeInt(0);                        // talon
            out.writeInt(0);                        // table
            out.writeInt(0);                        // endRoundApprovals
            out.writeInt(0);                        // discarded
            out.writeInt(0);                        // knownCardsByPlayer
        }
        return bos.toByteArray();
    }

    private static List<Card> cards(String... codes) {
        return List.of(codes).stream().map(Card::fromCode).toList();
    }
}
