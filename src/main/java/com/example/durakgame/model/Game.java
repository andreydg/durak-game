package com.example.durakgame.model;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

public class Game {
    public static final int MAX_HAND_SIZE = 6;

    private final String code;
    private final Instant createdAt;
    private final List<Player> players = new ArrayList<>();
    private final String hostPlayerId;
    private final Deque<Card> talon = new ArrayDeque<>();
    private final List<AttackEntry> table = new ArrayList<>();
    private final Set<Rank> tableRanks = new HashSet<>();

    private GameStatus status = GameStatus.LOBBY;
    private Suit trumpSuit;
    private Card trumpCard;
    private int attackerIndex = -1;
    private int defenderIndex = -1;
    private String loserPlayerId;

    public Game(String code, Player host) {
        this.code = code;
        this.createdAt = Instant.now();
        this.players.add(host);
        this.hostPlayerId = host.getId();
    }

    public synchronized Player addPlayer(String playerName, int maxPlayers) {
        ensureLobby();

        if (players.size() >= maxPlayers) {
            throw new IllegalStateException("Game is full");
        }

        boolean duplicateName = players.stream()
                .anyMatch(player -> player.getName().equalsIgnoreCase(playerName));
        if (duplicateName) {
            throw new IllegalStateException("Player name is already taken in this game");
        }

        Player player = new Player(playerName);
        players.add(player);
        return player;
    }

    public synchronized void start(String playerId) {
        ensureLobby();
        if (!Objects.equals(hostPlayerId, playerId)) {
            throw new IllegalStateException("Only host can start the game");
        }
        if (players.size() < 2) {
            throw new IllegalStateException("At least 2 players are required");
        }

        assignTeamsIfNeeded();
        initDeckAndDeal();
        chooseFirstAttacker();
        this.defenderIndex = nextEligibleIndex(attackerIndex);
        this.status = GameStatus.IN_PROGRESS;
    }

    public synchronized void attack(String playerId, Card card) {
        ensureInProgress();
        if (table.size() >= maxAllowedAttackCards()) {
            throw new IllegalStateException("Attack limit reached for this round");
        }

        Player attacker = getPlayerById(playerId);
        int attackerSeat = indexOfPlayer(playerId);
        if (attackerSeat == defenderIndex) {
            throw new IllegalStateException("Defender cannot attack");
        }
        if (isTeammate(attackerSeat, defenderIndex)) {
            throw new IllegalStateException("Cannot attack your teammate");
        }
        if (!attacker.removeCard(card)) {
            throw new IllegalStateException("Card is not in player's hand");
        }
        if (!table.isEmpty() && !tableRanks.contains(card.rank())) {
            attacker.addCard(card);
            throw new IllegalStateException("Podkidnoy attack must match an existing rank on table");
        }

        table.add(new AttackEntry(card, playerId));
        tableRanks.add(card.rank());
    }

    public synchronized void defend(String playerId, Card attackCard, Card defenseCard) {
        ensureInProgress();
        ensureDefender(playerId);
        Player defender = getPlayerById(playerId);
        if (!defender.removeCard(defenseCard)) {
            throw new IllegalStateException("Card is not in defender hand");
        }

        AttackEntry entry = table.stream()
                .filter(it -> !it.isDefended() && it.getAttackCard().equals(attackCard))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Attack card to defend not found"));

        if (!canBeat(entry.getAttackCard(), defenseCard)) {
            defender.addCard(defenseCard);
            throw new IllegalStateException("Defense card does not beat attack card");
        }

        entry.defendWith(defenseCard);
        tableRanks.add(defenseCard.rank());
    }

    public synchronized void transfer(String playerId, Card transferCard) {
        ensureInProgress();
        ensureDefender(playerId);
        if (table.isEmpty()) {
            throw new IllegalStateException("Cannot transfer before first attack");
        }
        boolean anyDefended = table.stream().anyMatch(AttackEntry::isDefended);
        if (anyDefended) {
            throw new IllegalStateException("Transfer is allowed only before defending starts");
        }
        Rank initialRank = table.getFirst().getAttackCard().rank();
        if (transferCard.rank() != initialRank) {
            throw new IllegalStateException("Transfer card rank must match current attack");
        }

        int nextDefenderIndex = nextEligibleIndex(defenderIndex);
        Player nextDefender = players.get(nextDefenderIndex);
        int futureAttackCount = table.size() + 1;
        if (nextDefender.handSize() < futureAttackCount) {
            throw new IllegalStateException("Next defender has too few cards for transfer");
        }

        Player defender = players.get(defenderIndex);
        if (!defender.removeCard(transferCard)) {
            throw new IllegalStateException("Card is not in defender hand");
        }

        table.add(new AttackEntry(transferCard, defender.getId()));
        tableRanks.add(transferCard.rank());
        attackerIndex = defenderIndex;
        defenderIndex = nextDefenderIndex;
    }

    public synchronized void takeCards(String playerId) {
        ensureInProgress();
        ensureDefender(playerId);
        if (table.isEmpty()) {
            throw new IllegalStateException("No cards on table");
        }

        Player defender = players.get(defenderIndex);
        List<Card> allCards = new ArrayList<>();
        for (AttackEntry entry : table) {
            allCards.add(entry.getAttackCard());
            if (entry.getDefenseCard() != null) {
                allCards.add(entry.getDefenseCard());
            }
        }
        defender.addCards(allCards);
        clearTable();

        refillHandsAfterRound();
        attackerIndex = nextEligibleIndex(defenderIndex);
        defenderIndex = nextEligibleIndex(attackerIndex);
        updateFinishState();
    }

    public synchronized void endRound(String playerId) {
        ensureInProgress();
        ensureDefender(playerId);
        if (table.isEmpty()) {
            throw new IllegalStateException("No active bout");
        }
        boolean allDefended = table.stream().allMatch(AttackEntry::isDefended);
        if (!allDefended) {
            throw new IllegalStateException("Not all attack cards are defended");
        }

        clearTable();
        refillHandsAfterRound();
        attackerIndex = defenderIndex;
        defenderIndex = nextEligibleIndex(attackerIndex);
        updateFinishState();
    }

    public String getCode() {
        return code;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized List<Player> getPlayers() {
        return Collections.unmodifiableList(new ArrayList<>(players));
    }

    public GameStatus getStatus() {
        return status;
    }

    public Suit getTrumpSuit() {
        return trumpSuit;
    }

    public Card getTrumpCard() {
        return trumpCard;
    }

    public synchronized List<AttackEntry> getTable() {
        return Collections.unmodifiableList(new ArrayList<>(table));
    }

    public int getTalonSize() {
        return talon.size();
    }

    public String getHostPlayerId() {
        return hostPlayerId;
    }

    public String getAttackerPlayerId() {
        if (attackerIndex < 0 || attackerIndex >= players.size()) {
            return null;
        }
        return players.get(attackerIndex).getId();
    }

    public String getDefenderPlayerId() {
        if (defenderIndex < 0 || defenderIndex >= players.size()) {
            return null;
        }
        return players.get(defenderIndex).getId();
    }

    public String getLoserPlayerId() {
        return loserPlayerId;
    }

    private void ensureLobby() {
        if (status != GameStatus.LOBBY) {
            throw new IllegalStateException("Game has already started");
        }
    }

    private void ensureInProgress() {
        if (status != GameStatus.IN_PROGRESS) {
            throw new IllegalStateException("Game is not in progress");
        }
    }

    private void ensureDefender(String playerId) {
        if (!Objects.equals(getDefenderPlayerId(), playerId)) {
            throw new IllegalStateException("Only defender can perform this action");
        }
    }

    private void assignTeamsIfNeeded() {
        if (players.size() == 4) {
            for (int i = 0; i < players.size(); i++) {
                players.get(i).setTeam(i % 2);
            }
            return;
        }
        for (Player player : players) {
            player.setTeam(null);
        }
    }

    private void initDeckAndDeal() {
        List<Card> deck = new ArrayList<>(36);
        for (Suit suit : Suit.values()) {
            for (Rank rank : Rank.values()) {
                deck.add(new Card(rank, suit));
            }
        }
        Collections.shuffle(deck);

        for (int round = 0; round < MAX_HAND_SIZE; round++) {
            for (Player player : players) {
                player.addCard(deck.removeFirst());
            }
        }

        trumpCard = deck.getLast();
        trumpSuit = trumpCard.suit();
        talon.addAll(deck);
    }

    private void chooseFirstAttacker() {
        this.attackerIndex = players.stream()
                .filter(player -> player.getHand().stream().anyMatch(card -> card.suit() == trumpSuit))
                .min(Comparator.comparing(this::lowestTrumpStrength))
                .map(this::indexOfPlayer)
                .orElse(0);
    }

    private int lowestTrumpStrength(Player player) {
        return player.getHand().stream()
                .filter(card -> card.suit() == trumpSuit)
                .map(card -> card.rank().strength())
                .min(Integer::compareTo)
                .orElse(Integer.MAX_VALUE);
    }

    private boolean canBeat(Card attack, Card defense) {
        if (defense.suit() == attack.suit() && defense.rank().strength() > attack.rank().strength()) {
            return true;
        }
        return defense.suit() == trumpSuit && attack.suit() != trumpSuit;
    }

    private int maxAllowedAttackCards() {
        return players.get(defenderIndex).handSize();
    }

    private void clearTable() {
        table.clear();
        tableRanks.clear();
    }

    private void refillHandsAfterRound() {
        int index = attackerIndex;
        do {
            refillPlayer(players.get(index));
            index = (index + 1) % players.size();
        } while (index != attackerIndex);
    }

    private void refillPlayer(Player player) {
        while (player.handSize() < MAX_HAND_SIZE && !talon.isEmpty()) {
            player.addCard(talon.removeFirst());
        }
    }

    private void updateFinishState() {
        if (!talon.isEmpty()) {
            return;
        }
        List<Player> remaining = players.stream()
                .filter(player -> player.handSize() > 0)
                .toList();
        if (remaining.size() <= 1) {
            status = GameStatus.FINISHED;
            loserPlayerId = remaining.isEmpty() ? null : remaining.getFirst().getId();
        }
    }

    private boolean isTeammate(int firstIndex, int secondIndex) {
        Integer t1 = players.get(firstIndex).getTeam();
        Integer t2 = players.get(secondIndex).getTeam();
        return t1 != null && Objects.equals(t1, t2);
    }

    private Player getPlayerById(String playerId) {
        return players.stream()
                .filter(player -> player.getId().equals(playerId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Player not found in this game"));
    }

    private int indexOfPlayer(String playerId) {
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).getId().equals(playerId)) {
                return i;
            }
        }
        throw new NoSuchElementException("Player not found in this game");
    }

    private int indexOfPlayer(Player player) {
        return indexOfPlayer(player.getId());
    }

    private int nextEligibleIndex(int startIndex) {
        int current = startIndex;
        for (int i = 0; i < players.size(); i++) {
            current = (current + 1) % players.size();
            Player candidate = players.get(current);
            boolean outOfGame = talon.isEmpty() && candidate.handSize() == 0;
            if (!outOfGame) {
                return current;
            }
        }
        return startIndex;
    }
}
