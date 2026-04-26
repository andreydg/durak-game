package com.example.durakgame.service;

import com.example.durakgame.controller.dto.LobbyGameSummary;
import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Player;
import com.example.durakgame.model.ViewerLegalMoves;
import com.example.durakgame.service.autoplay.AutoPlayAction;
import com.example.durakgame.service.autoplay.AutoPlayDecisionEngine;
import com.example.durakgame.service.store.GameStore;
import com.example.durakgame.websocket.GameWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class GameService {
    private static final Logger log = LoggerFactory.getLogger(GameService.class);
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final int MAX_PLAYERS = 4;

    private final SecureRandom random = new SecureRandom();
    private final GameStore gameStore;
    private final AutoPlayDecisionEngine autoPlayDecisionEngine;
    private final Set<String> autoPlayRunning = ConcurrentHashMap.newKeySet();

    private record AutoPlayRunResult(boolean changed, boolean continueLater) {
    }

    public GameService(GameStore gameStore, AutoPlayDecisionEngine autoPlayDecisionEngine) {
        this.gameStore = gameStore;
        this.autoPlayDecisionEngine = autoPlayDecisionEngine;
    }

    public Game createGame(String hostName) {
        String resolved = resolveDisplayName(hostName, List.of());
        Player host = new Player(resolved);
        String code = generateUniqueCode();
        Game game = new Game(code, host);
        gameStore.save(game);
        log.info("game_created code={} hostPlayerId={} hostName={}", game.getCode(), host.getId(), host.getName());
        return game;
    }

    public Game getGame(String gameCode) {
        String normalizedCode = normalizeCode(gameCode);
        return gameStore.findByCode(normalizedCode)
                .orElseThrow(() -> new NoSuchElementException("Game not found"));
    }

    public Player joinGame(String gameCode, String playerName) {
        Game game = getGame(gameCode);
        List<String> taken = game.getPlayers().stream().map(Player::getName).toList();
        String resolved = resolveDisplayName(playerName, taken);
        Player joined = game.addPlayer(resolved, MAX_PLAYERS);
        if (game.getStatus() == GameStatus.LOBBY && game.getPlayers().size() == MAX_PLAYERS) {
            game.start(game.getHostPlayerId());
            log.info("game_started code={} hostPlayerId={} players={}", game.getCode(), game.getHostPlayerId(), game.getPlayers().size());
        }
        gameStore.save(game);
        log.info("game_joined code={} playerId={} playerName={} players={}", game.getCode(), joined.getId(), joined.getName(), game.getPlayers().size());
        scheduleAutoPlay(game.getCode());
        return joined;
    }

    public Player addBot(String gameCode, String hostPlayerId, String botName) {
        Game game = getGame(gameCode);
        if (!Objects.equals(game.getHostPlayerId(), hostPlayerId)) {
            throw new IllegalStateException("Only host can add bots");
        }
        List<String> taken = game.getPlayers().stream().map(Player::getName).toList();
        String resolved;
        if (botName == null || botName.trim().isEmpty()) {
            resolved = GuestNameGenerator.randomBotNameDistinctFrom(taken);
        } else {
            String base = resolveDisplayName(botName, List.of());
            String withSuffix = base.endsWith(" Elektronik") ? base : (base + " Elektronik");
            resolved = resolveDisplayName(withSuffix, taken);
        }
        Player bot = game.addPlayer(resolved, MAX_PLAYERS, true);
        if (game.getStatus() == GameStatus.LOBBY && game.getPlayers().size() == MAX_PLAYERS) {
            game.start(game.getHostPlayerId());
        }
        gameStore.save(game);
        log.info("game_joined code={} playerId={} playerName={} players={}",
                game.getCode(), bot.getId(), bot.getName(), game.getPlayers().size());
        scheduleAutoPlay(game.getCode());
        return bot;
    }

    /**
     * Non-blank trimmed names must be 2–24 chars; blank picks a funny guest name
     * unique for join (when {@code alreadyTaken} is non-empty).
     */
    private String resolveDisplayName(String raw, List<String> alreadyTaken) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) {
            if (alreadyTaken.isEmpty()) {
                return GuestNameGenerator.randomName();
            }
            return GuestNameGenerator.randomNameDistinctFrom(alreadyTaken);
        }
        if (t.length() < 2) {
            throw new IllegalArgumentException("Name must be at least 2 characters (or leave blank for a random name)");
        }
        if (t.length() > 24) {
            throw new IllegalArgumentException("Name must be at most 24 characters");
        }
        return t;
    }

    public Game startGame(String gameCode, String playerId) {
        Game game = getGame(gameCode);
        game.start(playerId);
        gameStore.save(game);
        log.info("game_started code={} hostPlayerId={} players={}", game.getCode(), playerId, game.getPlayers().size());
        scheduleAutoPlay(game.getCode());
        return game;
    }

    public Game attack(String gameCode, String playerId, Card card) {
        Game game = getGame(gameCode);
        game.attack(playerId, card);
        gameStore.save(game);
        log.debug("game_action code={} action=attack playerId={} card={} tableSize={}",
                game.getCode(), playerId, card.code(), game.getTable().size());
        scheduleAutoPlay(game.getCode());
        return game;
    }

    public Game defend(String gameCode, String playerId, Card attackCard, Card defendCard) {
        Game game = getGame(gameCode);
        game.defend(playerId, attackCard, defendCard);
        gameStore.save(game);
        log.debug("game_action code={} action=defend playerId={} attackCard={} defenseCard={} tableSize={}",
                game.getCode(), playerId, attackCard.code(), defendCard.code(), game.getTable().size());
        scheduleAutoPlay(game.getCode());
        return game;
    }

    public Game transfer(String gameCode, String playerId, Card card) {
        Game game = getGame(gameCode);
        game.transfer(playerId, card);
        gameStore.save(game);
        log.debug("game_action code={} action=transfer playerId={} card={} defenderPlayerId={} tableSize={}",
                game.getCode(), playerId, card.code(), game.getDefenderPlayerId(), game.getTable().size());
        scheduleAutoPlay(game.getCode());
        return game;
    }

    public Game takeCards(String gameCode, String playerId) {
        Game game = getGame(gameCode);
        game.takeCards(playerId);
        gameStore.save(game);
        log.debug("game_action code={} action=take_cards playerId={} takeLimit={} tableSize={}",
                game.getCode(), playerId, game.getTakeLimit(), game.getTable().size());
        scheduleAutoPlay(game.getCode());
        return game;
    }

    public Game endRound(String gameCode, String playerId) {
        Game game = getGame(gameCode);
        GameStatus before = game.getStatus();
        game.endRound(playerId);
        gameStore.save(game);
        log.debug("game_action code={} action=end_round playerId={} statusBefore={} statusAfter={} tableSize={}",
                game.getCode(), playerId, before, game.getStatus(), game.getTable().size());
        if (before != GameStatus.FINISHED && game.getStatus() == GameStatus.FINISHED) {
            log.info("game_ended code={} loserPlayerId={}", game.getCode(), game.getLoserPlayerId());
        }
        scheduleAutoPlay(game.getCode());
        return game;
    }

    /**
     * Leave room:
     * - in lobby: remove player; remove room when host leaves or room becomes empty;
     * - after start: host leaving removes room, non-host leaving resets game to lobby.
     * Returns true when the whole game room was removed.
     */
    public boolean leaveGame(String gameCode, String playerId) {
        String normalizedCode = normalizeCode(gameCode);
        Game game = getGame(normalizedCode);
        boolean hostLeaving = Objects.equals(game.getHostPlayerId(), playerId);

        if (game.getStatus() != GameStatus.LOBBY) {
            if (hostLeaving) {
                gameStore.deleteByCode(normalizedCode);
                return true;
            }
            boolean removed = game.removePlayerAndResetToLobby(playerId);
            if (!removed) {
                throw new NoSuchElementException("Player not found in this game");
            }
            gameStore.save(game);
            return false;
        }

        boolean removed = game.removePlayerFromLobby(playerId);
        if (!removed) {
            throw new NoSuchElementException("Player not found in this game");
        }
        if (hostLeaving || game.getPlayers().isEmpty()) {
            gameStore.deleteByCode(normalizedCode);
            return true;
        }
        gameStore.save(game);
        return false;
    }

    public int getMaxPlayers() {
        return MAX_PLAYERS;
    }

    /**
     * Open lobby rooms waiting for players (same server instance only).
     */
    public List<LobbyGameSummary> listOpenLobbies() {
        return gameStore.listAll().stream()
                .filter(g -> g.getStatus() == GameStatus.LOBBY)
                .filter(g -> g.getPlayers().size() < MAX_PLAYERS)
                .map(g -> {
                    String hostName = g.getPlayers().stream()
                            .filter(p -> p.getId().equals(g.getHostPlayerId()))
                            .map(Player::getName)
                            .findFirst()
                            .orElse("?");
                    List<String> playerNames = g.getPlayers().stream()
                            .map(Player::getName)
                            .toList();
                    return new LobbyGameSummary(
                            g.getCode(),
                            hostName,
                            playerNames,
                            g.getPlayers().size(),
                            MAX_PLAYERS
                    );
                })
                .sorted(Comparator.comparing(LobbyGameSummary::code))
                .toList();
    }

    private String generateUniqueCode() {
        for (int attempts = 0; attempts < 1000; attempts++) {
            String candidate = randomCode();
            if (!gameStore.existsByCode(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to allocate unique game code");
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            int index = random.nextInt(CODE_ALPHABET.length());
            code.append(CODE_ALPHABET.charAt(index));
        }
        return code.toString();
    }

    private String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private void scheduleAutoPlay(String gameCode) {
        String normalizedCode = normalizeCode(gameCode);
        if (normalizedCode.isBlank() || !autoPlayRunning.add(normalizedCode)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            boolean continueLater = false;
            try {
                Game game = getGame(normalizedCode);
                AutoPlayRunResult result = runAutoPlayLoop(game);
                continueLater = result.continueLater();
                if (result.changed()) {
                    gameStore.save(game);
                    GameWebSocketHandler.broadcastGameUpdated(game.getCode(), game.getVersion());
                }
            } catch (NoSuchElementException ignored) {
                // Room may have been closed before the background bot turn started.
            } catch (RuntimeException ex) {
                log.warn("autoplay_background_failed code={} message={}", normalizedCode, ex.getMessage());
            } finally {
                autoPlayRunning.remove(normalizedCode);
            }
            if (continueLater) {
                CompletableFuture.delayedExecutor(250, TimeUnit.MILLISECONDS)
                        .execute(() -> scheduleAutoPlay(normalizedCode));
            }
        });
    }

    private AutoPlayRunResult runAutoPlayLoop(Game game) {
        boolean changed = false;
        for (int i = 0; i < 48; i++) {
            if (game.getStatus() != GameStatus.IN_PROGRESS) {
                return new AutoPlayRunResult(changed, false);
            }
            boolean advanced = false;
            for (Player player : game.getPlayers()) {
                if (!player.isBot()) {
                    continue;
                }
                ViewerLegalMoves legalMoves = game.computeViewerLegalMoves(player.getId());
                if (!hasAnyPlayableMove(legalMoves)) {
                    log.info("autoplay_skip code={} playerId={} playerName={} reason=no_legal_moves",
                            game.getCode(), player.getId(), player.getName());
                    continue;
                }
                if (shouldWaitForDefender(game, legalMoves)) {
                    log.info("autoplay_skip code={} playerId={} playerName={} reason=waiting_for_defender",
                            game.getCode(), player.getId(), player.getName());
                    continue;
                }
                log.info("autoplay_consider code={} playerId={} playerName={} canAttack={} canDefend={} canTransfer={} canTake={} canEndRound={}",
                        game.getCode(),
                        player.getId(),
                        player.getName(),
                        legalMoves.canAttack(),
                        legalMoves.canDefend(),
                        legalMoves.canTransfer(),
                        legalMoves.canTake(),
                        legalMoves.canEndRound());
                String thinkingMessage = thinkingMessage(game, legalMoves);
                GameWebSocketHandler.broadcastBotThinking(game.getCode(), player.getId(), true, thinkingMessage);
                AutoPlayAction action;
                try {
                    action = forcedLocalAction(legalMoves);
                    if (action == null) {
                        action = autoPlayDecisionEngine.choose(game, player.getId(), legalMoves);
                    } else {
                        log.info("autoplay_local_forced_action code={} playerId={} playerName={} action={}",
                                game.getCode(), player.getId(), player.getName(), action);
                        simulateThinkingPause();
                    }
                } finally {
                    GameWebSocketHandler.broadcastBotThinking(game.getCode(), player.getId(), false);
                }
                if (!isLegal(action, legalMoves)) {
                    log.info("autoplay_skip code={} playerId={} playerName={} reason=illegal_or_empty_action action={}",
                            game.getCode(), player.getId(), player.getName(), action);
                    continue;
                }
                try {
                    applyAction(game, player.getId(), action);
                    log.info("autoplay_applied code={} playerId={} playerName={} action={}",
                            game.getCode(), player.getId(), player.getName(), action);
                    changed = true;
                    if (action.type() == AutoPlayAction.Type.END_ROUND) {
                        return new AutoPlayRunResult(true, true);
                    }
                    advanced = true;
                    break;
                } catch (RuntimeException ignored) {
                    // Ignore bad model decisions that became stale between compute and apply.
                }
            }
            if (!advanced) {
                return new AutoPlayRunResult(changed, false);
            }
        }
        return new AutoPlayRunResult(changed, false);
    }

    private AutoPlayAction forcedLocalAction(ViewerLegalMoves legalMoves) {
        boolean canMoveCard = legalMoves.canAttack() || legalMoves.canDefend() || legalMoves.canTransfer();
        if (canMoveCard) {
            return null;
        }
        if (legalMoves.canTake() && !legalMoves.canEndRound()) {
            return AutoPlayAction.take();
        }
        if (legalMoves.canEndRound() && !legalMoves.canTake()) {
            return AutoPlayAction.endRound();
        }
        return null;
    }

    private void simulateThinkingPause() {
        long delayMs = 2000L + random.nextInt(1001);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String thinkingMessage(Game game, ViewerLegalMoves legalMoves) {
        if (legalMoves.canDefend() || legalMoves.canTransfer() || legalMoves.canTake()) {
            return "planning defence...";
        }
        if (legalMoves.canAttack()) {
            return game.isTakingCardsInProgress() ? "planning throw-in..." : "planning attack...";
        }
        if (legalMoves.canEndRound()) {
            return game.isTakingCardsInProgress() ? "planning end round..." : "planning attack...";
        }
        return "thinking...";
    }

    private boolean shouldWaitForDefender(Game game, ViewerLegalMoves legalMoves) {
        if (game.isTakingCardsInProgress()) {
            return false;
        }
        long undefended = game.getTable().stream()
                .filter(entry -> !entry.isDefended())
                .count();
        if (undefended == 0) {
            return false;
        }
        /*
         * Pace bot attacks like a human table: once an attack is unanswered, attackers
         * wait for the defender to beat, transfer, or take before throwing in again.
         */
        return legalMoves.canAttack() && !legalMoves.canDefend() && !legalMoves.canTransfer() && !legalMoves.canTake();
    }

    private boolean hasAnyPlayableMove(ViewerLegalMoves legalMoves) {
        return legalMoves.canAttack()
                || legalMoves.canDefend()
                || legalMoves.canTransfer()
                || legalMoves.canTake()
                || legalMoves.canEndRound();
    }

    private boolean isLegal(AutoPlayAction action, ViewerLegalMoves legalMoves) {
        if (action == null) {
            return false;
        }
        return switch (action.type()) {
            case ATTACK -> legalMoves.canAttack() && legalMoves.attackableCardCodes().contains(action.cardCode());
            case DEFEND -> legalMoves.canDefend()
                    && legalMoves.defensesByAttackCard().containsKey(action.attackCardCode())
                    && legalMoves.defensesByAttackCard().get(action.attackCardCode()).contains(action.cardCode());
            case TRANSFER -> legalMoves.canTransfer() && legalMoves.transferableCardCodes().contains(action.cardCode());
            case TAKE -> legalMoves.canTake();
            case END_ROUND -> legalMoves.canEndRound();
        };
    }

    private void applyAction(Game game, String playerId, AutoPlayAction action) {
        switch (action.type()) {
            case ATTACK -> game.attack(playerId, Card.fromCode(action.cardCode()));
            case DEFEND -> game.defend(playerId, Card.fromCode(action.attackCardCode()), Card.fromCode(action.cardCode()));
            case TRANSFER -> game.transfer(playerId, Card.fromCode(action.cardCode()));
            case TAKE -> game.takeCards(playerId);
            case END_ROUND -> game.endRound(playerId);
        }
    }
}
