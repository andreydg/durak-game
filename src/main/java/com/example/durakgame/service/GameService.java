package com.example.durakgame.service;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.Player;
import com.example.durakgame.model.Card;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
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
        return game.addPlayer(resolved, MAX_PLAYERS);
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

    public int getMaxPlayers() {
        return MAX_PLAYERS;
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
