package com.example.durakgame.service;

import com.example.durakgame.controller.dto.LobbyGameSummary;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Player;
import com.example.durakgame.model.Card;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GameService {
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final int MAX_PLAYERS = 4;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Game> games = new ConcurrentHashMap<>();

    public Game createGame(String hostName) {
        String resolved = resolveDisplayName(hostName, List.of());
        Player host = new Player(resolved);
        String code = generateUniqueCode();
        Game game = new Game(code, host);
        games.put(code, game);
        return game;
    }

    public Game getGame(String gameCode) {
        String normalizedCode = normalizeCode(gameCode);
        Game game = games.get(normalizedCode);
        if (game == null) {
            throw new NoSuchElementException("Game not found");
        }
        return game;
    }

    public Player joinGame(String gameCode, String playerName) {
        Game game = getGame(gameCode);
        List<String> taken = game.getPlayers().stream().map(Player::getName).toList();
        String resolved = resolveDisplayName(playerName, taken);
        Player joined = game.addPlayer(resolved, MAX_PLAYERS);
        if (game.getStatus() == GameStatus.LOBBY && game.getPlayers().size() == MAX_PLAYERS) {
            game.start(game.getHostPlayerId());
        }
        return joined;
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
        return game;
    }

    public Game attack(String gameCode, String playerId, Card card) {
        Game game = getGame(gameCode);
        game.attack(playerId, card);
        return game;
    }

    public Game defend(String gameCode, String playerId, Card attackCard, Card defendCard) {
        Game game = getGame(gameCode);
        game.defend(playerId, attackCard, defendCard);
        return game;
    }

    public Game transfer(String gameCode, String playerId, Card card) {
        Game game = getGame(gameCode);
        game.transfer(playerId, card);
        return game;
    }

    public Game takeCards(String gameCode, String playerId) {
        Game game = getGame(gameCode);
        game.takeCards(playerId);
        return game;
    }

    public Game endRound(String gameCode, String playerId) {
        Game game = getGame(gameCode);
        game.endRound(playerId);
        return game;
    }

    /**
     * Remove player from lobby, or remove whole room when host leaves / game already started.
     * Returns true when the whole game room was removed.
     */
    public boolean leaveGame(String gameCode, String playerId) {
        String normalizedCode = normalizeCode(gameCode);
        Game game = getGame(normalizedCode);

        if (game.getStatus() != GameStatus.LOBBY) {
            games.remove(normalizedCode);
            return true;
        }

        boolean hostLeaving = Objects.equals(game.getHostPlayerId(), playerId);
        boolean removed = game.removePlayerFromLobby(playerId);
        if (!removed) {
            throw new NoSuchElementException("Player not found in this game");
        }
        if (hostLeaving || game.getPlayers().isEmpty()) {
            games.remove(normalizedCode);
            return true;
        }
        return false;
    }

    public int getMaxPlayers() {
        return MAX_PLAYERS;
    }

    /**
     * Open lobby rooms waiting for players (same server instance only).
     */
    public List<LobbyGameSummary> listOpenLobbies() {
        return games.values().stream()
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
            if (!games.containsKey(candidate)) {
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
}
