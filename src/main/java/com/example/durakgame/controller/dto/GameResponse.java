package com.example.durakgame.controller.dto;

import com.example.durakgame.model.AttackEntry;
import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Player;
import com.example.durakgame.model.ViewerLegalMoves;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record GameResponse(
        String code,
        GameStatus status,
        int maxPlayers,
        int playerCount,
        Instant createdAt,
        String hostPlayerId,
        String attackerPlayerId,
        String defenderPlayerId,
        boolean takingCardsInProgress,
        String takingPlayerId,
        int takeLimit,
        String loserPlayerId,
        String trumpSuit,
        String trumpCard,
        int talonSize,
        List<TableCardPair> table,
        List<PlayerSummary> players,
        ViewerLegalMoves legalMoves
) {
    public static GameResponse from(Game game, int maxPlayers, String viewerPlayerId) {
        List<PlayerSummary> summaries = game.getPlayers().stream()
                .map(player -> PlayerSummary.from(player, viewerPlayerId))
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
                game.isTakingCardsInProgress(),
                game.getTakingPlayerId(),
                game.getTakeLimit(),
                game.getLoserPlayerId(),
                game.getTrumpSuit() == null ? null : game.getTrumpSuit().code(),
                game.getTrumpCard() == null ? null : game.getTrumpCard().code(),
                game.getTalonSize(),
                table,
                summaries,
                game.computeViewerLegalMoves(viewerPlayerId == null ? "" : viewerPlayerId)
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
        public static PlayerSummary from(Player player, String viewerPlayerId) {
            List<String> fullHand = player.getHand().stream().map(Card::code).toList();
            boolean canSeeHand = Objects.equals(player.getId(), viewerPlayerId);
            List<String> visibleHand = canSeeHand ? fullHand : List.of();
            return new PlayerSummary(
                    player.getId(),
                    player.getName(),
                    player.getJoinedAt(),
                    player.getTeam(),
                    fullHand.size(),
                    visibleHand
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
