package com.example.durakgame.service;

import java.security.SecureRandom;
import java.util.List;

/**
 * Silly guest names with Eastern European flavour for empty display names.
 */
public final class GuestNameGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String[] FIRST = {
            "Boris", "Yuri", "Vadim", "Sergei", "Piotr", "Dmitri", "Nikolai", "Ivan",
            "Alexei", "Mikhail", "Viktor", "Oleg", "Stanislav", "Bogdan", "Grigori",
            "Janusz", "Zbigniew", "Wojtek", "Krzysztof", "Lech", "Tadeusz", "Casimir",
            "Jaroslav", "Miloš", "Ljubomir", "Mircea", "Zoltán", "Béla", "Dragos",
            "Vladislav", "Anatoli", "Bronislav", "Gleb", "Igor", "Konstantin", "Lev",
            "Matvey", "Sasha", "Taras", "Vanya", "Yakov", "Zoran"
    };

    private GuestNameGenerator() {
    }

    /**
     * Random display name: a single first name only (2–24 chars).
     */
    public static String randomName() {
        return FIRST[RANDOM.nextInt(FIRST.length)];
    }

    /**
     * Pick a name not already used (case-insensitive) in {@code takenFrom}.
     */
    public static String randomNameDistinctFrom(List<String> takenFrom) {
        for (int attempt = 0; attempt < 120; attempt++) {
            String candidate = randomName();
            boolean clash = takenFrom.stream().anyMatch(t -> t.equalsIgnoreCase(candidate));
            if (!clash) {
                return candidate;
            }
        }
        return "Guest" + (1000 + RANDOM.nextInt(899_000));
    }
}
