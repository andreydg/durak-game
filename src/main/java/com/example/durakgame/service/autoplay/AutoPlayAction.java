package com.example.durakgame.service.autoplay;

public record AutoPlayAction(
        Type type,
        String cardCode,
        String attackCardCode
) {
    public enum Type {
        ATTACK,
        DEFEND,
        TRANSFER,
        TAKE,
        END_ROUND
    }

    public static AutoPlayAction attack(String cardCode) {
        return new AutoPlayAction(Type.ATTACK, cardCode, null);
    }

    public static AutoPlayAction defend(String attackCardCode, String cardCode) {
        return new AutoPlayAction(Type.DEFEND, cardCode, attackCardCode);
    }

    public static AutoPlayAction transfer(String cardCode) {
        return new AutoPlayAction(Type.TRANSFER, cardCode, null);
    }

    public static AutoPlayAction take() {
        return new AutoPlayAction(Type.TAKE, null, null);
    }

    public static AutoPlayAction endRound() {
        return new AutoPlayAction(Type.END_ROUND, null, null);
    }
}
