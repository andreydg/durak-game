package com.example.durakgame.service;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * One log line per deal transition, written once the change is saved, whichever path caused it:
 * a host start, a full table, quick play, a rematch, a player's or a bot's final end round, or a
 * player leaving. Counting the lines counts deals: {@code game_started} when one begins,
 * {@code game_ended} when it finishes, {@code game_interrupted} when a player leaves mid-deal.
 * Deals that start but neither end nor get interrupted were abandoned until the room expired.
 *
 * <p>{@code deal} pairs a deal's lines without storing anything new: the room code plus the time
 * the room last (re)opened, which every rematch and every reopened lobby renews.
 */
final class GameLifecycleLog {
    private static final Logger log = LoggerFactory.getLogger(GameLifecycleLog.class);

    private GameLifecycleLog() {
    }

    /** What a change needs to remember about the game before it is applied. */
    record Before(GameStatus status, String deal, int bouts) {
        static Before of(Game game) {
            return new Before(game.getStatus(), dealId(game), game.getBoutsCompleted());
        }
    }

    /** Logs the transition from {@code before} to the just-saved {@code game}, if its status changed. */
    static void afterSave(Before before, Game game, String trigger) {
        GameStatus after = game.getStatus();
        if (after == before.status()) {
            return;
        }
        if (after == GameStatus.IN_PROGRESS) {
            log.info("game_started code={} deal={} trigger={} hostPlayerId={} players={} humans={} bots={} publicRoom={}",
                    game.getCode(), dealId(game), trigger, game.getHostPlayerId(), game.getPlayers().size(),
                    humans(game), bots(game), game.isPublicRoom());
        } else if (before.status() == GameStatus.IN_PROGRESS && after == GameStatus.FINISHED) {
            log.info("game_ended code={} deal={} endedBy={} bouts={} loser={} loserPlayerId={} loserTeam={} humans={} bots={}",
                    game.getCode(), before.deal(), trigger, game.getBoutsCompleted(), loserKind(game),
                    game.getLoserPlayerId(), game.getLoserTeam(), humans(game), bots(game));
        } else if (before.status() == GameStatus.IN_PROGRESS) {
            interrupted(before, game, "player_left");
        }
    }

    /** The last human left mid-deal, so the room was removed instead of saved. */
    static void roomClosed(Before before, Game game) {
        if (before.status() == GameStatus.IN_PROGRESS) {
            interrupted(before, game, "room_closed");
        }
    }

    static String dealId(Game game) {
        return game.getCode() + "-" + Long.toString(game.getLobbyStartedAt().toEpochMilli(), 36);
    }

    private static void interrupted(Before before, Game game, String reason) {
        log.info("game_interrupted code={} deal={} reason={} bouts={} players={} humans={} bots={}",
                game.getCode(), before.deal(), reason, before.bouts(), game.getPlayers().size(),
                humans(game), bots(game));
    }

    private static String loserKind(Game game) {
        if (game.getLoserTeam() != null) {
            return "team";
        }
        if (game.getLoserPlayerId() == null) {
            return "none";
        }
        return game.getPlayers().stream()
                .filter(player -> Objects.equals(player.getId(), game.getLoserPlayerId()))
                .findFirst()
                .map(player -> player.isBot() ? "bot" : "human")
                .orElse("unknown");
    }

    private static long humans(Game game) {
        return game.getPlayers().stream().filter(player -> !player.isBot()).count();
    }

    private static long bots(Game game) {
        return game.getPlayers().stream().filter(Player::isBot).count();
    }
}
