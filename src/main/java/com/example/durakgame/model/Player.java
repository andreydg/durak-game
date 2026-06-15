package com.example.durakgame.model;

import java.io.Serializable;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public class Player implements Serializable {
    private static final SecureRandom SECRET_RANDOM = new SecureRandom();

    private final String id;
    private final String name;
    private final boolean bot;
    private final Instant joinedAt;
    /** Per-player capability token: proves ownership for hand reveal and actions. Never sent to other clients. */
    private final String secret;
    private final List<Card> hand = new ArrayList<>();
    private Integer team;

    public Player(String name) {
        this(name, false);
    }

    public Player(String name, boolean bot) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.bot = bot;
        this.joinedAt = Instant.now();
        this.secret = generateSecret();
    }

    public Player(String id, String name, Instant joinedAt) {
        this(id, name, joinedAt, false);
    }

    public Player(String id, String name, Instant joinedAt, boolean bot) {
        this(id, name, joinedAt, bot, generateSecret());
    }

    public Player(String id, String name, Instant joinedAt, boolean bot, String secret) {
        this.id = id;
        this.name = name;
        this.bot = bot;
        this.joinedAt = joinedAt;
        this.secret = secret == null ? "" : secret;
    }

    private static String generateSecret() {
        byte[] bytes = new byte[24];
        SECRET_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public String getId() {
        return id;
    }

    /** The owner's capability token. Blank for legacy games persisted before tokens existed. */
    public String getSecret() {
        return secret;
    }

    public String getName() {
        return name;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public boolean isBot() {
        return bot;
    }

    public synchronized List<Card> getHand() {
        return Collections.unmodifiableList(new ArrayList<>(hand));
    }

    public synchronized void addCard(Card card) {
        hand.add(card);
    }

    public synchronized void addCards(List<Card> cards) {
        hand.addAll(cards);
    }

    public synchronized boolean removeCard(Card card) {
        return hand.remove(card);
    }

    public synchronized int handSize() {
        return hand.size();
    }

    public synchronized void clearHand() {
        hand.clear();
    }

    public Integer getTeam() {
        return team;
    }

    public void setTeam(Integer team) {
        this.team = team;
    }

}
