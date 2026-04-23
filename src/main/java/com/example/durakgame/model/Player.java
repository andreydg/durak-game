package com.example.durakgame.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class Player implements Serializable {
    private final String id;
    private final String name;
    private final boolean bot;
    private final Instant joinedAt;
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
    }

    public Player(String id, String name, Instant joinedAt) {
        this(id, name, joinedAt, false);
    }

    public Player(String id, String name, Instant joinedAt, boolean bot) {
        this.id = id;
        this.name = name;
        this.bot = bot;
        this.joinedAt = joinedAt;
    }

    public String getId() {
        return id;
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
