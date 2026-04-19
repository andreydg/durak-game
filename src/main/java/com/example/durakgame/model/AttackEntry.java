package com.example.durakgame.model;

public class AttackEntry {
    private final Card attackCard;
    private final String attackerId;
    private Card defenseCard;

    public AttackEntry(Card attackCard, String attackerId) {
        this.attackCard = attackCard;
        this.attackerId = attackerId;
    }

    public Card getAttackCard() {
        return attackCard;
    }

    public String getAttackerId() {
        return attackerId;
    }

    public Card getDefenseCard() {
        return defenseCard;
    }

    public boolean isDefended() {
        return defenseCard != null;
    }

    public void defendWith(Card defenseCard) {
        this.defenseCard = defenseCard;
    }
}
