package com.example.durakgame.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class Player {
    private final String id;
    private final String name;
    private final Instant joinedAt;
    private final List<Card> hand = new ArrayList<>();
    private Integer team;

    public Player(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.joinedAt = Instant.now();
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
