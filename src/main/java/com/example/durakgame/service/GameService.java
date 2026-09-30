package com.example.durakgame.service;

import com.example.durakgame.controller.dto.LobbyGameSummary;
import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Player;
import com.example.durakgame.model.ViewerLegalMoves;
import com.example.durakgame.service.autoplay.AutoPlayAction;
import com.example.durakgame.service.autoplay.AutoPlayDecisionEngine;
import com.example.durakgame.service.autoplay.HeuristicAutoPlayDecisionEngine;
import com.example.durakgame.service.store.GameStore;
import com.example.durakgame.service.store.LobbyProjection;
import com.example.durakgame.service.store.StaleGameWriteException;
import com.example.durakgame.websocket.GameWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
    /* Max bot moves applied per scheduled loop before yielding the virtual thread back. */
    private static final int AUTO_PLAY_MAX_ITERATIONS = 48;
    /* Pause before the loop reschedules itself after a bout-ending move, so clients can render it. */
    private static final long AUTO_PLAY_RESCHEDULE_DELAY_MS = 250;
    /* Minimum bot "thinking" window so moves feel human-paced even when the decision is instant. */
    private static final long AUTO_PLAY_MIN_THINK_MS = 2000;
    private static final int AUTO_PLAY_THINK_JITTER_MS = 1001;
    /* Re-plans allowed per pass when a bot decision goes stale because another player moved first. */
    private static final int AUTO_PLAY_MAX_STALE_REPLANS = 5;
    /* Delayed retries after an unexpected failure (e.g. a store outage) before waiting for a read to resume the bot. */
    private static final int AUTO_PLAY_MAX_FAILURE_RETRIES = 3;
    private static final long AUTO_PLAY_FAILURE_RETRY_DELAY_MS = 2000;
    /* One retry covers a cross-instance lost-update race; the per-code lock prevents same-JVM races. */
    private static final int MUTATE_RETRY_ATTEMPTS = 2;

    private final SecureRandom random = new SecureRandom();
    private final GameStore gameStore;
    private final AutoPlayDecisionEngine autoPlayDecisionEngine;
    /* Last resort when the engine returns nothing usable: a bot with a legal move must always move. */
    private final AutoPlayDecisionEngine heuristicFallback;
    private final GameWebSocketHandler webSocketHandler;
    private final GameExpiryPolicy expiryPolicy;
    private final LobbyUpdatePublisher lobbyUpdatePublisher;
    /*
     * Games with a bot pass in flight. The value records whether another pass was requested while it
     * ran: a human move that lands while the bot deliberates must not be dropped, or the bot can end
     * up owing a move that nothing will ever schedule.
     */
    private final ConcurrentHashMap<String, Boolean> autoPlayRuns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> autoPlayFailures = new ConcurrentHashMap<>();
    /*
     * Serializes load->mutate->save per game code so concurrent actions (human + bot,
     * two humans) cannot clobber each other through separate decoded copies of the game.
     * Striped (fixed array, code-hash indexed) so the lock table stays bounded no matter
     * how many game codes are ever allocated; distinct codes may share a stripe, which only
     * adds rare, harmless contention.
     */
    private static final int LOCK_STRIPES = 64;
    private final ReentrantLock[] gameLocks = createStripes(LOCK_STRIPES);

    private static ReentrantLock[] createStripes(int count) {
        ReentrantLock[] locks = new ReentrantLock[count];
        for (int i = 0; i < count; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }
    /* Bot turns block for seconds (LLM call + pacing pauses); virtual threads keep that cheap. */
    private final ExecutorService autoPlayExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile CachedLobbies cachedLobbies;
    private final AtomicLong lobbyProjectionRevision = new AtomicLong();

    private record CachedLobbies(long atMs, long revision, List<LobbyGameSummary> summaries) {
    }

    @Autowired
    public GameService(
            GameStore gameStore,
            AutoPlayDecisionEngine autoPlayDecisionEngine,
            HeuristicAutoPlayDecisionEngine heuristicFallback,
            GameWebSocketHandler webSocketHandler,
            GameExpiryPolicy expiryPolicy,
            LobbyUpdatePublisher lobbyUpdatePublisher
    ) {
        this.gameStore = gameStore;
        this.autoPlayDecisionEngine = autoPlayDecisionEngine;
        this.heuristicFallback = heuristicFallback;
        this.webSocketHandler = webSocketHandler;
        this.expiryPolicy = expiryPolicy;
        this.lobbyUpdatePublisher = lobbyUpdatePublisher;
    }

    GameService(
            GameStore gameStore,
            AutoPlayDecisionEngine autoPlayDecisionEngine,
            GameWebSocketHandler webSocketHandler,
            GameExpiryPolicy expiryPolicy,
            LobbyUpdatePublisher lobbyUpdatePublisher
    ) {
        this(gameStore, autoPlayDecisionEngine, new HeuristicAutoPlayDecisionEngine(), webSocketHandler,
                expiryPolicy, lobbyUpdatePublisher);
    }

    GameService(
            GameStore gameStore,
            AutoPlayDecisionEngine autoPlayDecisionEngine,
            GameWebSocketHandler webSocketHandler,
            GameExpiryPolicy expiryPolicy
    ) {
        this(gameStore, autoPlayDecisionEngine, webSocketHandler, expiryPolicy, LobbyUpdatePublisher.noop());
    }

    GameService(
            GameStore gameStore,
            AutoPlayDecisionEngine autoPlayDecisionEngine,
            GameWebSocketHandler webSocketHandler
    ) {
        this(gameStore, autoPlayDecisionEngine, webSocketHandler, GameExpiryPolicy.defaults());
    }

    public Game createGame(String hostName) {
        return createGame(hostName, true);
    }

    public Game createGame(String hostName, boolean publicRoom) {
        String resolved = resolveDisplayName(hostName, List.of());
        Player host = new Player(resolved);
        String code = generateUniqueCode();
        Game game = new Game(code, host, publicRoom);
        gameStore.save(game);
        if (publicRoom) {
            publishLobbyChange();
        }
        log.info("game_created code={} hostPlayerId={} hostName={} publicRoom={}",
                game.getCode(), host.getId(), host.getName(), publicRoom);
        return game;
    }

    /** Creates a private two-player game and starts it immediately against one bot. */
    public Game createQuickGame(String hostName) {
        String resolved = resolveDisplayName(hostName, List.of());
        Player host = new Player(resolved);
        String code = generateUniqueCode();
        Game game = new Game(code, host, false);
        String botName = GuestNameGenerator.randomBotNameDistinctFrom(List.of(host.getName()));
        game.addPlayer(botName, MAX_PLAYERS, true);
        game.start(host.getId());
        gameStore.save(game);
        log.info("game_quick_started code={} hostPlayerId={} hostName={} botName={}",
                game.getCode(), host.getId(), host.getName(), botName);
        scheduleAutoPlay(code);
        return game;
    }

    public Game getGame(String gameCode) {
        String normalizedCode = normalizeCode(gameCode);
        Game game = gameStore.findByCode(normalizedCode)
                .orElseThrow(() -> new NoSuchElementException("Game not found"));
        if (expiryPolicy.isExpired(game, Instant.now())) {
            gameStore.deleteByCode(normalizedCode);
            if (game.isPublicRoom() && game.getStatus() == GameStatus.LOBBY) {
                publishLobbyChange();
            }
            log.info("game_expired code={} status={} lastActivityAt={}",
                    game.getCode(), game.getStatus(), game.getLastActivityAt());
            throw new RoomExpiredException();
        }
        return game;
    }

    public Game heartbeat(String gameCode, String playerId) {
        String normalizedCode = normalizeCode(gameCode);
        Game game = withGameLockRetryingStale(normalizedCode, () -> {
            Game g = getGame(normalizedCode);
            boolean seated = g.getPlayers().stream().anyMatch(player -> player.getId().equals(playerId));
            if (!seated) {
                throw new NoSuchElementException("Player not found in this game");
            }
            if (g.getStatus() != GameStatus.FINISHED) {
                g.markActive();
                gameStore.save(g);
                if (g.isPublicRoom() && g.getStatus() == GameStatus.LOBBY) {
                    // Expiry metadata changed, so bypass this instance's short projection cache.
                    // The visible lobby roster did not change, so avoid broadcasting to every client.
                    invalidateLobbyCache();
                }
            }
            return g;
        });
        resumeAutoPlayIfStalled(game);
        return game;
    }

    /**
     * True when {@code token} proves ownership of {@code playerId} in {@code game}. For games
     * persisted before per-player tokens existed (blank stored secret), the player id itself is
     * accepted as the token, so sessions and rooms in flight across the upgrade keep working.
     */
    public boolean isAuthorized(Game game, String playerId, String token) {
        if (playerId == null || token == null || token.isBlank()) {
            return false;
        }
        Player player = game.getPlayers().stream()
                .filter(p -> p.getId().equals(playerId))
                .findFirst()
                .orElse(null);
        if (player == null) {
            return false;
        }
        String secret = player.getSecret();
        if (secret == null || secret.isBlank()) {
            return token.equals(playerId);
        }
        return token.equals(secret);
    }

    /** Loads the game and rejects the request unless {@code token} proves ownership of {@code playerId}. */
    public void requireAuthorized(String gameCode, String playerId, String token) {
        Game game = getGame(gameCode);
        if (!isAuthorized(game, playerId, token)) {
            throw new UnauthorizedActionException();
        }
    }

    public Player joinGame(String gameCode, String playerName) {
        String normalizedCode = normalizeCode(gameCode);
        Player joined = withGameLockRetryingStale(normalizedCode, () -> {
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
            if (game.isPublicRoom()) {
                publishLobbyChange();
            }
            log.info("game_joined code={} playerId={} playerName={} players={}",
                    game.getCode(), player.getId(), player.getName(), game.getPlayers().size());
            return player;
        });
        scheduleAutoPlay(normalizedCode);
        return joined;
    }

    public Player addBot(String gameCode, String hostPlayerId, String botName) {
        String normalizedCode = normalizeCode(gameCode);
        Player bot = withGameLockRetryingStale(normalizedCode, () -> {
            Game game = getGame(normalizedCode);
            if (!Objects.equals(game.getHostPlayerId(), hostPlayerId)) {
                throw new IllegalStateException("Only host can add bots");
            }
            boolean hasBot = game.getPlayers().stream().anyMatch(Player::isBot);
            if (hasBot) {
                throw new IllegalStateException("Only one bot is allowed per table");
            }
            List<String> taken = game.getPlayers().stream().map(Player::getName).toList();
            String resolved = resolveBotName(botName, taken);
            Player added = game.addPlayer(resolved, MAX_PLAYERS, true);
            if (game.getStatus() == GameStatus.LOBBY && game.getPlayers().size() == MAX_PLAYERS) {
                game.start(game.getHostPlayerId());
            }
            gameStore.save(game);
            if (game.isPublicRoom()) {
                publishLobbyChange();
            }
            log.info("game_joined code={} playerId={} playerName={} players={}",
                    game.getCode(), added.getId(), added.getName(), game.getPlayers().size());
            return added;
        });
        scheduleAutoPlay(normalizedCode);
        return bot;
    }

    private String resolveBotName(String botName, List<String> taken) {
        if (botName == null || botName.trim().isEmpty()) {
            return GuestNameGenerator.randomBotNameDistinctFrom(taken);
        }
        String base = resolveDisplayName(botName, List.of());
        String withSuffix = base.endsWith(" Elektronik") ? base : (base + " Elektronik");
        return resolveDisplayName(withSuffix, taken);
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
        if (game.isPublicRoom()) {
            publishLobbyChange();
        }
        log.info("game_started code={} hostPlayerId={} players={}", game.getCode(), playerId, game.getPlayers().size());
        scheduleAutoPlay(game.getCode());
        return game;
    }

    public Game rematch(String gameCode, String playerId) {
        Game game = mutateGame(gameCode, g -> g.rematch(playerId));
        log.info("game_rematched code={} hostPlayerId={} players={}",
                game.getCode(), playerId, game.getPlayers().size());
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
        Game game = withGameLockRetryingStale(normalizedCode, () -> {
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
     * Intentionally leave a room. Browser disconnects do not call this operation: the
     * player session remains reconnectable. An in-progress game returns to the lobby after a
     * departure. A completed game keeps its result until the host chooses a rematch, and ownership
     * transfers when the departing player was the host.
     * Returns true when no human player remains and the room is removed.
     */
    public boolean leaveGame(String gameCode, String playerId) {
        String normalizedCode = normalizeCode(gameCode);
        return withGameLockRetryingStale(normalizedCode, () -> {
            Game game = getGame(normalizedCode);

            if (game.getStatus() == GameStatus.IN_PROGRESS) {
                boolean removed = game.removePlayerAndResetToLobby(playerId);
                if (!removed) {
                    throw new NoSuchElementException("Player not found in this game");
                }
            } else if (game.getStatus() == GameStatus.FINISHED) {
                boolean removed = game.removePlayerFromFinishedGame(playerId);
                if (!removed) {
                    throw new NoSuchElementException("Player not found in this game");
                }
            } else if (!game.removePlayerFromLobby(playerId)) {
                throw new NoSuchElementException("Player not found in this game");
            }

            boolean hasHumanPlayer = game.getPlayers().stream().anyMatch(player -> !player.isBot());
            if (!hasHumanPlayer) {
                gameStore.deleteByCode(normalizedCode);
                if (game.isPublicRoom()) {
                    publishLobbyChange();
                }
                return true;
            }
            gameStore.save(game);
            if (game.isPublicRoom()) {
                publishLobbyChange();
            }
            return false;
        });
    }

    public int getMaxPlayers() {
        return MAX_PLAYERS;
    }

    /**
     * Open lobby rooms waiting for players. Cached briefly as a safety net for reconnects
     * and health checks; normal clients refresh when the lobby websocket is invalidated.
     */
    public List<LobbyGameSummary> listOpenLobbies() {
        List<LobbyGameSummary> summaries = List.of();
        for (int attempt = 0; attempt < 2; attempt++) {
            long revisionAtStart = lobbyProjectionRevision.get();
            CachedLobbies cached = cachedLobbies;
            long now = System.currentTimeMillis();
            if (cached != null
                    && cached.revision() == revisionAtStart
                    && now - cached.atMs() < LOBBY_CACHE_TTL_MS) {
                return cached.summaries();
            }

            Instant instantNow = Instant.now();
            AtomicBoolean deletedExpiredLobby = new AtomicBoolean();
            summaries = gameStore.listOpenLobbySummaries().stream()
                    .filter(LobbyProjection::publicRoom)
                    .filter(p -> p.playerCount() < MAX_PLAYERS)
                    .filter(p -> {
                        if (!expiryPolicy.isExpired(
                                GameStatus.LOBBY, p.lastActivityAt(), p.lobbyStartedAt(), instantNow)) {
                            return true;
                        }
                        gameStore.deleteByCode(p.code());
                        deletedExpiredLobby.set(true);
                        log.info("game_expired code={} status=LOBBY lastActivityAt={}", p.code(), p.lastActivityAt());
                        return false;
                    })
                    .map(p -> new LobbyGameSummary(
                            p.code(),
                            p.hostName(),
                            p.playerNames(),
                            p.playerCount(),
                            MAX_PLAYERS,
                            p.lastActivityAt(),
                            expiryPolicy.expiresAt(GameStatus.LOBBY, p.lastActivityAt(), p.lobbyStartedAt())
                    ))
                    .sorted(Comparator.comparingInt(LobbyGameSummary::playerCount).reversed()
                            .thenComparing(LobbyGameSummary::code))
                    .toList();
            if (deletedExpiredLobby.get()) {
                publishLobbyChange();
            }

            long revisionAfterRead = lobbyProjectionRevision.get();
            if (revisionAfterRead == revisionAtStart) {
                cachedLobbies = new CachedLobbies(now, revisionAtStart, summaries);
                return summaries;
            }
        }
        return summaries;
    }

    /** Lobby fan-out is best effort and must never turn a successful game mutation into an error. */
    private void publishLobbyChange() {
        invalidateLobbyCache();
        try {
            lobbyUpdatePublisher.lobbiesChanged();
        } catch (RuntimeException ex) {
            log.warn("lobby_update_publish_failed message={}", ex.getMessage());
        }
    }

    private void invalidateLobbyCache() {
        lobbyProjectionRevision.incrementAndGet();
        cachedLobbies = null;
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
        ReentrantLock lock = gameLocks[Math.floorMod(normalizedCode.hashCode(), gameLocks.length)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs a load->mutate->save body under the per-code lock, retrying on a
     * {@link StaleGameWriteException} so a cross-instance lost-update race re-reads fresh
     * state and re-applies instead of surfacing a conflict to the player. The body must be
     * idempotent across retries (it re-loads the game each attempt).
     */
    private <T> T withGameLockRetryingStale(String normalizedCode, Supplier<T> body) {
        return withGameLock(normalizedCode, () -> {
            StaleGameWriteException lastStale = null;
            for (int attempt = 0; attempt < MUTATE_RETRY_ATTEMPTS; attempt++) {
                try {
                    return body.get();
                } catch (StaleGameWriteException stale) {
                    lastStale = stale;
                    log.info("mutate_stale_write_retry code={} attempt={} message={}",
                            normalizedCode, attempt, stale.getMessage());
                }
            }
            throw lastStale;
        });
    }

    private Game mutateGame(String gameCode, Consumer<Game> mutation) {
        String normalizedCode = normalizeCode(gameCode);
        return withGameLockRetryingStale(normalizedCode, () -> {
            Game game = getGame(normalizedCode);
            mutation.accept(game);
            gameStore.save(game);
            return game;
        });
    }

    /** Requests a bot pass for the game; if one is already running, it runs once more when it finishes. */
    private void scheduleAutoPlay(String gameCode) {
        String normalizedCode = normalizeCode(gameCode);
        if (normalizedCode.isBlank()) {
            return;
        }
        boolean[] start = {false};
        autoPlayRuns.compute(normalizedCode, (code, rerunRequested) -> {
            if (rerunRequested == null) {
                start[0] = true;
                return Boolean.FALSE;
            }
            return Boolean.TRUE;
        });
        if (start[0]) {
            startAutoPlay(normalizedCode);
        }
    }

    /**
     * Restarts a bot turn that nothing is driving any more — e.g. the instance restarted mid-turn or
     * the pass gave up after repeated failures. Read paths (game fetch, heartbeat) call this, so a
     * stalled table recovers as soon as anyone looks at it. No-op while a pass is in flight.
     */
    public void resumeAutoPlayIfStalled(Game game) {
        if (game.getStatus() != GameStatus.IN_PROGRESS || autoPlayRuns.containsKey(game.getCode())) {
            return;
        }
        boolean botOwesMove = game.getPlayers().stream()
                .filter(Player::isBot)
                .map(bot -> game.computeViewerLegalMoves(bot.getId()))
                .anyMatch(moves -> hasAnyPlayableMove(moves) && !shouldWaitForDefender(game, moves));
        if (botOwesMove && autoPlayRuns.putIfAbsent(game.getCode(), Boolean.FALSE) == null) {
            log.info("autoplay_resumed code={}", game.getCode());
            startAutoPlay(game.getCode());
        }
    }

    private void startAutoPlay(String code) {
        try {
            autoPlayExecutor.execute(() -> runAutoPlay(code));
        } catch (RejectedExecutionException ex) {
            autoPlayRuns.remove(code);
            log.warn("autoplay_rejected code={} message={}", code, ex.getMessage());
        }
    }

    private void runAutoPlayLater(String code, long delayMs) {
        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS, autoPlayExecutor)
                .execute(() -> runAutoPlay(code));
    }

    /** Runs bot passes while this task owns the game's {@link #autoPlayRuns} entry, then releases it. */
    private void runAutoPlay(String code) {
        while (true) {
            boolean yielded;
            try {
                yielded = runAutoPlayLoop(code);
                autoPlayFailures.remove(code);
            } catch (NoSuchElementException | RoomExpiredException ignored) {
                // The room closed or expired; there is no bot turn left to drive.
                autoPlayFailures.remove(code);
                yielded = false;
            } catch (RuntimeException ex) {
                int failures = autoPlayFailures.merge(code, 1, Integer::sum);
                log.warn("autoplay_background_failed code={} failures={} message={}",
                        code, failures, ex.getMessage(), ex);
                if (failures <= AUTO_PLAY_MAX_FAILURE_RETRIES) {
                    // Keep ownership through the back-off so a concurrent request cannot double-run.
                    runAutoPlayLater(code, AUTO_PLAY_FAILURE_RETRY_DELAY_MS * failures);
                    return;
                }
                autoPlayFailures.remove(code);
                log.warn("autoplay_gave_up code={} failures={}", code, failures);
                yielded = false;
            }
            if (yielded) {
                // A bout just ended: pause so clients can render it, keeping ownership meanwhile.
                runAutoPlayLater(code, AUTO_PLAY_RESCHEDULE_DELAY_MS);
                return;
            }
            boolean rerun = autoPlayRuns.compute(code, (key, rerunRequested) ->
                    Boolean.TRUE.equals(rerunRequested) ? Boolean.FALSE : null) != null;
            if (!rerun) {
                return;
            }
        }
    }

    /** Returns true when the loop should be rescheduled after a pause (yield after END_ROUND). */
    private boolean runAutoPlayLoop(String code) {
        int staleReplans = 0;
        for (int i = 0; i < AUTO_PLAY_MAX_ITERATIONS; i++) {
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
                String source;
                try {
                    /* The slow part (LLM call) runs without the game lock. */
                    action = forcedLocalAction(game, legalMoves);
                    source = "forced";
                    if (action == null) {
                        action = chooseWithEngine(game, player, legalMoves);
                        source = "engine";
                    } else {
                        log.info("autoplay_local_forced_action code={} playerId={} playerName={} action={}",
                                game.getCode(), player.getId(), player.getName(), action);
                    }
                    if (!isLegal(action, legalMoves)) {
                        // Never let an unusable decision end the bot's turn: nothing else would retry it.
                        log.warn("autoplay_invalid_decision code={} playerId={} source={} action={}",
                                game.getCode(), player.getId(), source, action);
                        action = heuristicFallback.choose(game, player.getId(), legalMoves);
                        source = "fallback";
                    }
                } finally {
                    ensureMinimumThinkingPause(thinkingStartedAtMs);
                    webSocketHandler.broadcastBotThinking(game.getCode(), player.getId(), false);
                }
                if (!isLegal(action, legalMoves)) {
                    log.warn("autoplay_skip code={} playerId={} playerName={} reason=no_legal_action action={}",
                            game.getCode(), player.getId(), player.getName(), action);
                    continue;
                }
                boolean applied = applyAutoPlayAction(code, player, action);
                if (!applied) {
                    /*
                     * Someone else moved while the bot deliberated, so its decision no longer fits.
                     * Re-plan against fresh state instead of ending the pass: the other player's own
                     * scheduling request may have been absorbed by this still-running pass.
                     */
                    if (++staleReplans > AUTO_PLAY_MAX_STALE_REPLANS) {
                        log.warn("autoplay_replan_limit code={} playerId={} replans={}",
                                game.getCode(), player.getId(), staleReplans);
                        return false;
                    }
                    advanced = true;
                    break;
                }
                log.info("autoplay_applied code={} playerId={} playerName={} source={} action={}",
                        game.getCode(), player.getId(), player.getName(), source, action);
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
        return withGameLockRetryingStale(code, () -> {
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

    /** An engine failure yields no decision (so the heuristic takes over) instead of aborting the turn. */
    private AutoPlayAction chooseWithEngine(Game game, Player player, ViewerLegalMoves legalMoves) {
        try {
            return autoPlayDecisionEngine.choose(game, player.getId(), legalMoves);
        } catch (RuntimeException ex) {
            log.warn("autoplay_engine_failed code={} playerId={} message={}",
                    game.getCode(), player.getId(), ex.getMessage());
            return null;
        }
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
        long minDelayMs = AUTO_PLAY_MIN_THINK_MS + random.nextInt(AUTO_PLAY_THINK_JITTER_MS);
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
