package com.example.durakgame.model;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final Set<String> endRoundApprovals = new HashSet<>();
    private boolean takingCardsInProgress = false;
    private int takeLimit = 0;

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

    public synchronized boolean removePlayerFromLobby(String playerId) {
        ensureLobby();
        return players.removeIf(player -> player.getId().equals(playerId));
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
        endRoundApprovals.clear();
        Player attacker = getPlayerById(playerId);
        int attackerSeat = indexOfPlayer(playerId);
        if (attackerSeat == defenderIndex || isTeammate(attackerSeat, defenderIndex)) {
            throw new IllegalStateException("Defender cannot attack");
        }

        if (takingCardsInProgress) {
            if (!isAttackingSideSeat(attackerSeat)) {
                throw new IllegalStateException("Only attacking-side players can throw in");
            }
            if (table.size() >= takeLimit) {
                throw new IllegalStateException("Cannot throw in more cards");
            }
        } else {
            int defenderHand = players.get(defenderIndex).handSize();
            long undefended = table.stream().filter(e -> !e.isDefended()).count();
            if (undefended >= defenderHand) {
                throw new IllegalStateException("Defender cannot cover more undefended attacks");
            }
        }

        if (!takingCardsInProgress && players.size() == 4 && table.isEmpty() && attackerSeat != attackerIndex) {
            throw new IllegalStateException("Only the opening attacker may play the first card this bout");
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
        endRoundApprovals.clear();
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
        endRoundApprovals.clear();
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
        endRoundApprovals.clear();
        if (takingCardsInProgress) {
            throw new IllegalStateException("Defender is already taking");
        }
        if (table.isEmpty()) {
            throw new IllegalStateException("No cards on table");
        }
        if (table.stream().allMatch(AttackEntry::isDefended)) {
            throw new IllegalStateException("All attacks are defended; end the round instead");
        }
        takingCardsInProgress = true;
        takeLimit = players.get(defenderIndex).handSize();
    }

    public synchronized void endRound(String playerId) {
        ensureInProgress();
        if (table.isEmpty()) {
            throw new IllegalStateException("No active bout");
        }
        int callerSeat = indexOfPlayer(playerId);
        boolean isAttackingSide = isAttackingSideSeat(callerSeat);
        if (!isAttackingSide) {
            throw new IllegalStateException("Only attacking-side players can end this bout");
        }

        if (!takingCardsInProgress) {
            boolean allDefended = table.stream().allMatch(AttackEntry::isDefended);
            if (!allDefended) {
                throw new IllegalStateException("Not all attack cards are defended");
            }
        }
        endRoundApprovals.add(playerId);
        if (!allRequiredEndRoundApprovalsPresent()) {
            return;
        }

        if (takingCardsInProgress) {
            finalizeTakeCardsRound();
        } else {
            clearTable();
            refillHandsAfterRound();
            attackerIndex = defenderIndex;
            defenderIndex = nextEligibleIndex(attackerIndex);
            updateFinishState();
        }
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

    public boolean isTakingCardsInProgress() {
        return takingCardsInProgress;
    }

    public String getTakingPlayerId() {
        return takingCardsInProgress ? getDefenderPlayerId() : null;
    }

    /**
     * Computes which actions and card plays are legal for {@code viewerPlayerId},
     * matching the checks in {@link #attack}, {@link #defend}, {@link #transfer}, etc.
     */
    public synchronized ViewerLegalMoves computeViewerLegalMoves(String viewerPlayerId) {
        if (viewerPlayerId == null || viewerPlayerId.isBlank()) {
            return ViewerLegalMoves.empty();
        }
        if (status == GameStatus.FINISHED) {
            return ViewerLegalMoves.empty();
        }
        if (status == GameStatus.LOBBY) {
            boolean canStart = Objects.equals(hostPlayerId, viewerPlayerId) && players.size() >= 2;
            return new ViewerLegalMoves(
                    canStart,
                    false,
                    false,
                    false,
                    false,
                    false,
                    List.of(),
                    List.of(),
                    Map.of()
            );
        }

        final Player viewer;
        try {
            viewer = getPlayerById(viewerPlayerId);
        } catch (NoSuchElementException ex) {
            return ViewerLegalMoves.empty();
        }

        int viewerSeat = indexOfPlayer(viewerPlayerId);
        boolean isDefenderSeat = viewerSeat == defenderIndex;
        boolean isAttackerSeat = viewerSeat == attackerIndex;

        List<String> attackable = new ArrayList<>();
        if (takingCardsInProgress) {
            if (isAttackingSideSeat(viewerSeat) && table.size() < takeLimit) {
                for (Card card : viewer.getHand()) {
                    if (table.isEmpty() || tableRanks.contains(card.rank())) {
                        attackable.add(card.code());
                    }
                }
            }
        } else {
            int defenderHand = players.get(defenderIndex).handSize();
            long undefended = table.stream().filter(e -> !e.isDefended()).count();
            boolean openingLeadReserved =
                    players.size() == 4 && table.isEmpty() && viewerSeat != attackerIndex;
            if (!isDefenderSeat
                    && !isTeammate(viewerSeat, defenderIndex)
                    && undefended < defenderHand
                    && !openingLeadReserved) {
                for (Card card : viewer.getHand()) {
                    if (table.isEmpty() || tableRanks.contains(card.rank())) {
                        attackable.add(card.code());
                    }
                }
            }
        }
        boolean canAttack = !attackable.isEmpty();

        List<String> transferable = new ArrayList<>();
        if (!takingCardsInProgress && isDefenderSeat && !table.isEmpty()) {
            boolean anyDefended = table.stream().anyMatch(AttackEntry::isDefended);
            if (!anyDefended) {
                Rank initialRank = table.getFirst().getAttackCard().rank();
                int nextDefenderIndex = nextEligibleIndex(defenderIndex);
                Player nextDefender = players.get(nextDefenderIndex);
                int futureAttackCount = table.size() + 1;
                if (nextDefender.handSize() >= futureAttackCount) {
                    for (Card card : viewer.getHand()) {
                        if (card.rank() == initialRank) {
                            transferable.add(card.code());
                        }
                    }
                }
            }
        }
        boolean canTransfer = !transferable.isEmpty();

        boolean allDefended = !table.isEmpty() && table.stream().allMatch(AttackEntry::isDefended);
        /* Take pile only while something is still undefended; once all are beaten, round ends via End round. */
        boolean canTake = !takingCardsInProgress && isDefenderSeat && !table.isEmpty() && !allDefended;
        boolean isAttackingSideSeat = isAttackingSideSeat(viewerSeat);
        boolean canEndRound = (takingCardsInProgress || allDefended)
                && !isDefenderSeat
                && isAttackingSideSeat
                && !endRoundApprovals.contains(viewerPlayerId);

        Map<String, List<String>> defensesByAttack = new LinkedHashMap<>();
        boolean canDefend = false;
        if (!takingCardsInProgress && isDefenderSeat) {
            for (AttackEntry entry : table) {
                if (entry.isDefended()) {
                    continue;
                }
                Card attackCard = entry.getAttackCard();
                List<String> validDefense = new ArrayList<>();
                for (Card defenseCard : viewer.getHand()) {
                    if (canBeat(attackCard, defenseCard)) {
                        validDefense.add(defenseCard.code());
                    }
                }
                if (!validDefense.isEmpty()) {
                    defensesByAttack.put(attackCard.code(), List.copyOf(validDefense));
                    canDefend = true;
                }
            }
        }

        return new ViewerLegalMoves(
                false,
                canAttack,
                canDefend,
                canTransfer,
                canTake,
                canEndRound,
                List.copyOf(attackable),
                List.copyOf(transferable),
                Map.copyOf(defensesByAttack)
        );
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

    private void clearTable() {
        table.clear();
        tableRanks.clear();
        endRoundApprovals.clear();
        takingCardsInProgress = false;
        takeLimit = 0;
    }

    private void finalizeTakeCardsRound() {
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

    private void refillHandsAfterRound() {
        int index = attackerIndex;
        do {
            refillPlayer(players.get(index));
            index = prevSeat(index);
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

    private boolean isAttackingSideSeat(int seatIndex) {
        return seatIndex != defenderIndex && !isTeammate(seatIndex, defenderIndex);
    }

    private boolean allRequiredEndRoundApprovalsPresent() {
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            if (isOutOfGame(p)) {
                continue;
            }
            boolean required = isAttackingSideSeat(i);
            if (required && !endRoundApprovals.contains(p.getId())) {
                return false;
            }
        }
        return true;
    }

    private boolean isOutOfGame(Player player) {
        return talon.isEmpty() && player.handSize() == 0;
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

    /**
     * Next seat in counterclockwise table order (index decreases, wrapping).
     */
    private int prevSeat(int index) {
        int n = players.size();
        return (index - 1 + n) % n;
    }

    private int nextEligibleIndex(int startIndex) {
        int current = startIndex;
        for (int i = 0; i < players.size(); i++) {
            current = prevSeat(current);
            Player candidate = players.get(current);
            boolean outOfGame = talon.isEmpty() && candidate.handSize() == 0;
            if (!outOfGame) {
                return current;
            }
        }
        return startIndex;
    }
}
