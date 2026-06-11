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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Service
public class GameService {
    private static final Logger log = LoggerFactory.getLogger(GameService.class);
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final int MAX_PLAYERS = 4;
    private static final long LOBBY_CACHE_TTL_MS = 3000;

    private final SecureRandom random = new SecureRandom();
    private final GameStore gameStore;
    private final AutoPlayDecisionEngine autoPlayDecisionEngine;
    private final GameWebSocketHandler webSocketHandler;
    private final Set<String> autoPlayRunning = ConcurrentHashMap.newKeySet();
    /*
     * Serializes load->mutate->save per game code so concurrent actions (human + bot,
     * two humans) cannot clobber each other through separate decoded copies of the game.
     */
    private final ConcurrentHashMap<String, ReentrantLock> gameLocks = new ConcurrentHashMap<>();
    /* Bot turns block for seconds (LLM call + pacing pauses); virtual threads keep that cheap. */
    private final ExecutorService autoPlayExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile CachedLobbies cachedLobbies;

    private record CachedLobbies(long atMs, List<LobbyGameSummary> summaries) {
    }

    public GameService(
            GameStore gameStore,
            AutoPlayDecisionEngine autoPlayDecisionEngine,
            GameWebSocketHandler webSocketHandler
    ) {
        this.gameStore = gameStore;
        this.autoPlayDecisionEngine = autoPlayDecisionEngine;
        this.webSocketHandler = webSocketHandler;
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
        String normalizedCode = normalizeCode(gameCode);
        Player joined = withGameLock(normalizedCode, () -> {
            Game game = getGame(normalizedCode);
            List<String> taken = game.getPlayers().stream().map(Player::getName).toList();
            String resolved = resolveDisplayName(playerName, taken);
            Player player = game.addPlayer(resolved, MAX_PLAYERS);
            if (game.getStatus() == GameStatus.LOBBY && game.getPlayers().size() == MAX_PLAYERS) {
                game.start(game.getHostPlayerId());
                log.info("game_started code={} hostPlayerId={} players={}",
                        game.getCode(), game.getHostPlayerId(), game.getPlayers().size());
            }
            gameStore.save(game);
            log.info("game_joined code={} playerId={} playerName={} players={}",
                    game.getCode(), player.getId(), player.getName(), game.getPlayers().size());
            return player;
        });
        scheduleAutoPlay(normalizedCode);
        return joined;
    }

    public Player addBot(String gameCode, String hostPlayerId, String botName) {
        String normalizedCode = normalizeCode(gameCode);
        Player bot = withGameLock(normalizedCode, () -> {
            Game game = getGame(normalizedCode);
            if (!Objects.equals(game.getHostPlayerId(), hostPlayerId)) {
                throw new IllegalStateException("Only host can add bots");
            }
            boolean hasBot = game.getPlayers().stream().anyMatch(Player::isBot);
            if (hasBot) {
                throw new IllegalStateException("Only one bot is allowed per table");
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
            Player added = game.addPlayer(resolved, MAX_PLAYERS, true);
            if (game.getStatus() == GameStatus.LOBBY && game.getPlayers().size() == MAX_PLAYERS) {
                game.start(game.getHostPlayerId());
            }
            gameStore.save(game);
            log.info("game_joined code={} playerId={} playerName={} players={}",
                    game.getCode(), added.getId(), added.getName(), game.getPlayers().size());
            return added;
        });
        scheduleAutoPlay(normalizedCode);
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
        Game game = mutateGame(gameCode, g -> g.start(playerId));
        log.info("game_started code={} hostPlayerId={} players={}", game.getCode(), playerId, game.getPlayers().size());
        scheduleAutoPlay(game.getCode());
        return game;
    }

    public Game attack(String gameCode, String playerId, Card card) {
        Game game = mutateGame(gameCode, g -> g.attack(playerId, card));
        log.debug("game_action code={} action=attack playerId={} card={} tableSize={}",
                game.getCode(), playerId, card.code(), game.getTable().size());
        scheduleAutoPlay(game.getCode());
        return game;
    }

    public Game defend(String gameCode, String playerId, Card attackCard, Card defendCard) {
        Game game = mutateGame(gameCode, g -> g.defend(playerId, attackCard, defendCard));
        log.debug("game_action code={} action=defend playerId={} attackCard={} defenseCard={} tableSize={}",
                game.getCode(), playerId, attackCard.code(), defendCard.code(), game.getTable().size());
        scheduleAutoPlay(game.getCode());
        return game;
    }

    public Game transfer(String gameCode, String playerId, Card card) {
        Game game = mutateGame(gameCode, g -> g.transfer(playerId, card));
        log.debug("game_action code={} action=transfer playerId={} card={} defenderPlayerId={} tableSize={}",
                game.getCode(), playerId, card.code(), game.getDefenderPlayerId(), game.getTable().size());
        scheduleAutoPlay(game.getCode());
        return game;
    }

    public Game takeCards(String gameCode, String playerId) {
        Game game = mutateGame(gameCode, g -> g.takeCards(playerId));
        log.debug("game_action code={} action=take_cards playerId={} takeLimit={} tableSize={}",
                game.getCode(), playerId, game.getTakeLimit(), game.getTable().size());
        scheduleAutoPlay(game.getCode());
        return game;
    }

    public Game endRound(String gameCode, String playerId) {
        String normalizedCode = normalizeCode(gameCode);
        Game game = withGameLock(normalizedCode, () -> {
            Game g = getGame(normalizedCode);
            GameStatus before = g.getStatus();
            g.endRound(playerId);
            gameStore.save(g);
            log.debug("game_action code={} action=end_round playerId={} statusBefore={} statusAfter={} tableSize={}",
                    g.getCode(), playerId, before, g.getStatus(), g.getTable().size());
            if (before != GameStatus.FINISHED && g.getStatus() == GameStatus.FINISHED) {
                log.info("game_ended code={} loserPlayerId={}", g.getCode(), g.getLoserPlayerId());
            }
            return g;
        });
        scheduleAutoPlay(normalizedCode);
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
        return withGameLock(normalizedCode, () -> {
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
        });
    }

    public int getMaxPlayers() {
        return MAX_PLAYERS;
    }

    /**
     * Open lobby rooms waiting for players. Cached briefly because every connected
     * client polls this and each refresh hits the store.
     */
    public List<LobbyGameSummary> listOpenLobbies() {
        CachedLobbies cached = cachedLobbies;
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.atMs() < LOBBY_CACHE_TTL_MS) {
            return cached.summaries();
        }
        List<LobbyGameSummary> summaries = gameStore.listOpenLobbies().stream()
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
        cachedLobbies = new CachedLobbies(now, summaries);
        return summaries;
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

    private <T> T withGameLock(String normalizedCode, Supplier<T> action) {
        ReentrantLock lock = gameLocks.computeIfAbsent(normalizedCode, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private Game mutateGame(String gameCode, Consumer<Game> mutation) {
        String normalizedCode = normalizeCode(gameCode);
        return withGameLock(normalizedCode, () -> {
            Game game = getGame(normalizedCode);
            mutation.accept(game);
            gameStore.save(game);
            return game;
        });
    }

    private void scheduleAutoPlay(String gameCode) {
        String normalizedCode = normalizeCode(gameCode);
        if (normalizedCode.isBlank() || !autoPlayRunning.add(normalizedCode)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            boolean continueLater = false;
            try {
                continueLater = runAutoPlayLoop(normalizedCode);
            } catch (NoSuchElementException ignored) {
                // Room may have been closed before the background bot turn started.
            } catch (RuntimeException ex) {
                log.warn("autoplay_background_failed code={} message={}", normalizedCode, ex.getMessage(), ex);
            } finally {
                autoPlayRunning.remove(normalizedCode);
            }
            if (continueLater) {
                CompletableFuture.delayedExecutor(250, TimeUnit.MILLISECONDS, autoPlayExecutor)
                        .execute(() -> scheduleAutoPlay(normalizedCode));
            }
        }, autoPlayExecutor);
    }

    /** Returns true when the loop should be rescheduled after a pause (yield after END_ROUND). */
    private boolean runAutoPlayLoop(String code) {
        for (int i = 0; i < 48; i++) {
            /* Fresh load each iteration: humans may act while the bot deliberates. */
            Game game = getGame(code);
            if (game.getStatus() != GameStatus.IN_PROGRESS) {
                return false;
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
                webSocketHandler.broadcastBotThinking(game.getCode(), player.getId(), true, thinkingMessage);
                long thinkingStartedAtMs = System.currentTimeMillis();
                AutoPlayAction action;
                try {
                    /* The slow part (LLM call) runs without the game lock. */
                    action = forcedLocalAction(game, legalMoves);
                    if (action == null) {
                        action = autoPlayDecisionEngine.choose(game, player.getId(), legalMoves);
                    } else {
                        log.info("autoplay_local_forced_action code={} playerId={} playerName={} action={}",
                                game.getCode(), player.getId(), player.getName(), action);
                    }
                } finally {
                    ensureMinimumThinkingPause(thinkingStartedAtMs);
                    webSocketHandler.broadcastBotThinking(game.getCode(), player.getId(), false);
                }
                if (action == null) {
                    log.info("autoplay_skip code={} playerId={} playerName={} reason=no_action",
                            game.getCode(), player.getId(), player.getName());
                    continue;
                }
                boolean applied = applyAutoPlayAction(code, player, action);
                if (!applied) {
                    continue;
                }
                log.info("autoplay_applied code={} playerId={} playerName={} action={}",
                        game.getCode(), player.getId(), player.getName(), action);
                if (action.type() == AutoPlayAction.Type.END_ROUND) {
                    return true;
                }
                advanced = true;
                break;
            }
            if (!advanced) {
                return false;
            }
        }
        return false;
    }

    /**
     * Re-loads the game under the per-code lock and re-validates the action against
     * fresh state before applying, so a stale LLM decision can't revert human moves.
     */
    private boolean applyAutoPlayAction(String code, Player player, AutoPlayAction action) {
        return withGameLock(code, () -> {
            Game fresh = getGame(code);
            if (fresh.getStatus() != GameStatus.IN_PROGRESS) {
                return false;
            }
            ViewerLegalMoves freshMoves = fresh.computeViewerLegalMoves(player.getId());
            if (!isLegal(action, freshMoves)) {
                log.info("autoplay_skip code={} playerId={} playerName={} reason=illegal_or_stale_action action={}",
                        fresh.getCode(), player.getId(), player.getName(), action);
                return false;
            }
            try {
                applyAction(fresh, player.getId(), action);
            } catch (RuntimeException ex) {
                log.info("autoplay_skip code={} playerId={} playerName={} reason=apply_failed action={} message={}",
                        fresh.getCode(), player.getId(), player.getName(), action, ex.getMessage());
                return false;
            }
            gameStore.save(fresh);
            webSocketHandler.broadcastGameUpdated(fresh.getCode(), fresh.getVersion());
            return true;
        });
    }

    private AutoPlayAction forcedLocalAction(Game game, ViewerLegalMoves legalMoves) {
        AutoPlayAction defenseDiscipline = forcedDefenseDisciplineAction(game, legalMoves);
        if (defenseDiscipline != null) {
            return defenseDiscipline;
        }
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

    private AutoPlayAction forcedDefenseDisciplineAction(Game game, ViewerLegalMoves legalMoves) {
        if (game.isTakingCardsInProgress() || !legalMoves.canTake()) {
            return null;
        }
        boolean hasDefendedCardOnTable = game.getTable().stream().anyMatch(entry -> entry.getDefenseCard() != null);
        if (!hasDefendedCardOnTable && legalMoves.canDefend()
                && !canDefendAllCurrentAttacks(legalMoves.defensesByAttackCard())) {
            return AutoPlayAction.take();
        }
        return null;
    }

    private boolean canDefendAllCurrentAttacks(Map<String, List<String>> defensesByAttackCard) {
        if (defensesByAttackCard.isEmpty()) {
            return false;
        }
        List<Map.Entry<String, List<String>>> attacks = defensesByAttackCard.entrySet().stream()
                .sorted(Comparator
                        .comparingInt((Map.Entry<String, List<String>> entry) -> entry.getValue().size())
                        .thenComparing(Map.Entry::getKey))
                .toList();
        return canAssignDefenses(attacks, 0, new HashSet<>());
    }

    private boolean canAssignDefenses(List<Map.Entry<String, List<String>>> attacks, int index, Set<String> usedDefenses) {
        if (index >= attacks.size()) {
            return true;
        }
        List<String> defenseOptions = new ArrayList<>(attacks.get(index).getValue());
        defenseOptions.sort(String::compareTo);
        for (String defense : defenseOptions) {
            if (usedDefenses.add(defense)) {
                if (canAssignDefenses(attacks, index + 1, usedDefenses)) {
                    return true;
                }
                usedDefenses.remove(defense);
            }
        }
        return false;
    }

    private void ensureMinimumThinkingPause(long startedAtMs) {
        long minDelayMs = 2000L + random.nextInt(1001);
        long elapsedMs = System.currentTimeMillis() - startedAtMs;
        long remainingMs = minDelayMs - elapsedMs;
        if (remainingMs <= 0) {
            return;
        }
        try {
            Thread.sleep(remainingMs);
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
