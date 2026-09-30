package com.example.durakgame.controller;

import com.example.durakgame.model.Card;
import com.example.durakgame.model.Rank;
import com.example.durakgame.model.Suit;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Card images used to be PNG files. A tab opened before the switch to WebP keeps running its old
 * script, which still asks for {@code /cards/<code>.png} whenever it draws a card, so those URLs
 * redirect to the WebP file. The redirect is a plain 302, which browsers do not cache, so rolling
 * back to a PNG build finds its own files again.
 */
@Controller
public class LegacyCardImageController {
    /** Every card the server can deal, plus the back: exactly the PNG names the old frontend used. */
    static final Set<String> CARD_NAMES = Stream.concat(
                    Arrays.stream(Rank.values()).flatMap(rank ->
                            Arrays.stream(Suit.values()).map(suit -> new Card(rank, suit).code())),
                    Stream.of("BACK"))
            .collect(Collectors.toUnmodifiableSet());

    @GetMapping("/cards/{name}.png")
    public ResponseEntity<Void> legacyPng(@PathVariable String name) {
        if (!CARD_NAMES.contains(name)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/cards/" + name + ".webp")).build();
    }
}
