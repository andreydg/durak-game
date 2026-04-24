package com.example.durakgame.service.store;

import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Rank;
import com.example.durakgame.model.Suit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class GameBinaryCodec {
    private static final byte[] MAGIC = new byte[]{'D', 'G', '1'};
    private static final int VERSION = 1;

    byte[] encode(Game game) {
        Game.Snapshot snapshot = game.toSnapshot();
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            out.write(MAGIC);
            out.writeByte(VERSION);
            out.writeUTF(snapshot.code());
            out.writeLong(snapshot.createdAtEpochMs());
            out.writeUTF(snapshot.hostPlayerId());
            out.writeByte(snapshot.status().ordinal());
            out.writeBoolean(snapshot.trumpSuit() != null);
            if (snapshot.trumpSuit() != null) {
                out.writeByte(snapshot.trumpSuit().ordinal());
            }
            out.writeBoolean(snapshot.trumpCard() != null);
            if (snapshot.trumpCard() != null) {
                out.writeByte(cardToId(snapshot.trumpCard()));
            }
            out.writeInt(snapshot.attackerIndex());
            out.writeInt(snapshot.defenderIndex());
            out.writeBoolean(snapshot.loserPlayerId() != null);
            if (snapshot.loserPlayerId() != null) {
                out.writeUTF(snapshot.loserPlayerId());
            }
            out.writeBoolean(snapshot.takingCardsInProgress());
            out.writeInt(snapshot.takeLimit());
            out.writeLong(snapshot.version());

            out.writeInt(snapshot.players().size());
            for (Game.PlayerSnapshot player : snapshot.players()) {
                out.writeUTF(player.id());
                out.writeUTF(player.name());
                out.writeLong(player.joinedAtEpochMs());
                out.writeBoolean(player.team() != null);
                if (player.team() != null) {
                    out.writeInt(player.team());
                }
                out.writeInt(player.hand().size());
                for (Card card : player.hand()) {
                    out.writeByte(cardToId(card));
                }
            }

            out.writeInt(snapshot.talon().size());
            for (Card card : snapshot.talon()) {
                out.writeByte(cardToId(card));
            }

            out.writeInt(snapshot.table().size());
            for (Game.AttackSnapshot attack : snapshot.table()) {
                out.writeByte(cardToId(attack.attackCard()));
                out.writeBoolean(attack.defenseCard() != null);
                if (attack.defenseCard() != null) {
                    out.writeByte(cardToId(attack.defenseCard()));
                }
                out.writeUTF(attack.attackerId());
            }

            out.writeInt(snapshot.endRoundApprovals().size());
            for (String playerId : snapshot.endRoundApprovals()) {
                out.writeUTF(playerId);
            }

            out.flush();
            return bos.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to encode game snapshot", ex);
        }
    }

    Game decode(byte[] payload) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(payload);
             DataInputStream in = new DataInputStream(bis)) {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!matchesMagic(magic)) {
                throw new IllegalStateException("Unknown payload format");
            }
            int formatVersion = in.readUnsignedByte();
            if (formatVersion != VERSION) {
                throw new IllegalStateException("Unsupported payload version: " + formatVersion);
            }

            String code = in.readUTF();
            long createdAt = in.readLong();
            String hostPlayerId = in.readUTF();
            GameStatus status = GameStatus.values()[in.readUnsignedByte()];
            Suit trumpSuit = in.readBoolean() ? Suit.values()[in.readUnsignedByte()] : null;
            Card trumpCard = in.readBoolean() ? idToCard(in.readUnsignedByte()) : null;
            int attackerIndex = in.readInt();
            int defenderIndex = in.readInt();
            String loserPlayerId = in.readBoolean() ? in.readUTF() : null;
            boolean taking = in.readBoolean();
            int takeLimit = in.readInt();
            long version = in.readLong();

            int playerCount = in.readInt();
            List<Game.PlayerSnapshot> players = new ArrayList<>(playerCount);
            for (int i = 0; i < playerCount; i++) {
                String id = in.readUTF();
                String name = in.readUTF();
                long joinedAt = in.readLong();
                Integer team = in.readBoolean() ? in.readInt() : null;
                int handCount = in.readInt();
                List<Card> hand = new ArrayList<>(handCount);
                for (int j = 0; j < handCount; j++) {
                    hand.add(idToCard(in.readUnsignedByte()));
                }
                players.add(new Game.PlayerSnapshot(id, name, joinedAt, team, hand));
            }

            int talonCount = in.readInt();
            List<Card> talon = new ArrayList<>(talonCount);
            for (int i = 0; i < talonCount; i++) {
                talon.add(idToCard(in.readUnsignedByte()));
            }

            int tableCount = in.readInt();
            List<Game.AttackSnapshot> table = new ArrayList<>(tableCount);
            for (int i = 0; i < tableCount; i++) {
                Card attackCard = idToCard(in.readUnsignedByte());
                Card defenseCard = in.readBoolean() ? idToCard(in.readUnsignedByte()) : null;
                String attackerId = in.readUTF();
                table.add(new Game.AttackSnapshot(attackCard, defenseCard, attackerId));
            }

            int approvalsCount = in.readInt();
            Set<String> approvals = new HashSet<>();
            for (int i = 0; i < approvalsCount; i++) {
                approvals.add(in.readUTF());
            }

            return Game.fromSnapshot(new Game.Snapshot(
                    code,
                    createdAt,
                    hostPlayerId,
                    status,
                    trumpSuit,
                    trumpCard,
                    attackerIndex,
                    defenderIndex,
                    loserPlayerId,
                    taking,
                    takeLimit,
                    version,
                    players,
                    talon,
                    table,
                    approvals
            ));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to decode game snapshot", ex);
        }
    }

    boolean isCodecPayload(byte[] payload) {
        if (payload.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (payload[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesMagic(byte[] bytes) {
        if (bytes.length != MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private int cardToId(Card card) {
        return card.suit().ordinal() * 9 + card.rank().ordinal();
    }

    private Card idToCard(int id) {
        if (id < 0 || id >= 36) {
            throw new IllegalStateException("Invalid card id: " + id);
        }
        Suit suit = Suit.values()[id / 9];
        Rank rank = Rank.values()[id % 9];
        return new Card(rank, suit);
    }
}
