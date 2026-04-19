package com.example.durakgame.controller.dto;

import com.example.durakgame.model.AttackEntry;
import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Player;

import java.time.Instant;
import java.util.List;

public record GameResponse(
        String code,
        GameStatus status,
        int maxPlayers,
        int playerCount,
        Instant createdAt,
        String hostPlayerId,
        String attackerPlayerId,
        String defenderPlayerId,
        String loserPlayerId,
        String trumpSuit,
        String trumpCard,
        int talonSize,
        List<TableCardPair> table,
        List<PlayerSummary> players
) {
    public static GameResponse from(Game game, int maxPlayers) {
        List<PlayerSummary> summaries = game.getPlayers().stream()
                .map(PlayerSummary::from)
                .toList();
        List<TableCardPair> table = game.getTable().stream()
                .map(TableCardPair::from)
                .toList();

        return new GameResponse(
                game.getCode(),
                game.getStatus(),
                maxPlayers,
                summaries.size(),
                game.getCreatedAt(),
                game.getHostPlayerId(),
                game.getAttackerPlayerId(),
                game.getDefenderPlayerId(),
                game.getLoserPlayerId(),
                game.getTrumpSuit() == null ? null : game.getTrumpSuit().name(),
                game.getTrumpCard() == null ? null : game.getTrumpCard().code(),
                game.getTalonSize(),
                table,
                summaries
        );
    }

    public record PlayerSummary(
            String id,
            String name,
            Instant joinedAt,
            Integer team,
            int handSize,
            List<String> hand
    ) {
        public static PlayerSummary from(Player player) {
            List<String> hand = player.getHand().stream().map(Card::code).toList();
            return new PlayerSummary(
                    player.getId(),
                    player.getName(),
                    player.getJoinedAt(),
                    player.getTeam(),
                    hand.size(),
                    hand
            );
        }
    }

    public record TableCardPair(String attackCard, String defenseCard, String attackerId) {
        public static TableCardPair from(AttackEntry entry) {
            return new TableCardPair(
                    entry.getAttackCard().code(),
                    entry.getDefenseCard() == null ? null : entry.getDefenseCard().code(),
                    entry.getAttackerId()
            );
        }
    }
}
