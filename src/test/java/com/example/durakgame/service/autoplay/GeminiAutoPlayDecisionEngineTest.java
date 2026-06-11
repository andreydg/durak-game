package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.ViewerLegalMoves;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GeminiAutoPlayDecisionEngineTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeminiAutoPlayDecisionEngine engine = new GeminiAutoPlayDecisionEngine(
            new HeuristicAutoPlayDecisionEngine(),
            false,
            "",
            "gemini-test",
            "http://localhost",
            "HIGH",
            true,
            30,
            "auto",
            "auto",
            "auto",
            "auto",
            1000
    );

    private static final ViewerLegalMoves ATTACKER_MOVES = new ViewerLegalMoves(
            false, true, false, false, false, false,
            List.of("6D", "7S"), List.of(), Map.of());

    private static final ViewerLegalMoves DEFENDER_MOVES = new ViewerLegalMoves(
            false, false, true, true, true, false,
            List.of(), List.of("7S"), Map.of("7H", List.of("KH", "8S")));

    @Test
    void parsesTypedAttackAction() throws IOException {
        AutoPlayAction action = parse("{\"type\":\"ATTACK\",\"cardCode\":\"6D\"}", ATTACKER_MOVES);
        assertNotNull(action);
        assertEquals(AutoPlayAction.Type.ATTACK, action.type());
        assertEquals("6D", action.cardCode());
    }

    @Test
    void parsesCodeFencedJson() throws IOException {
        AutoPlayAction action = parse("```json\n{\"type\":\"ATTACK\",\"cardCode\":\"6D\"}\n```", ATTACKER_MOVES);
        assertNotNull(action);
        assertEquals(AutoPlayAction.Type.ATTACK, action.type());
        assertEquals("6D", action.cardCode());
    }

    @Test
    void infersTypeFromActionLabelAndCardFromCardsArray() throws IOException {
        AutoPlayAction action = parse(
                "{\"strategy\":\"open low\",\"action\":\"Attack\",\"cards\":[\"6D\"]}",
                ATTACKER_MOVES);
        assertNotNull(action);
        assertEquals(AutoPlayAction.Type.ATTACK, action.type());
        assertEquals("6D", action.cardCode());
    }

    @Test
    void normalizesDefenderAttackIntoTransfer() throws IOException {
        // A defender labeling a same-rank play as "Attack" must become a TRANSFER.
        AutoPlayAction action = parse(
                "{\"type\":\"ATTACK\",\"cards\":[\"7S\"],\"action\":\"Attack\"}",
                DEFENDER_MOVES);
        assertNotNull(action);
        assertEquals(AutoPlayAction.Type.TRANSFER, action.type());
        assertEquals("7S", action.cardCode());
    }

    @Test
    void parsesTakeWithoutRevealingCards() throws IOException {
        AutoPlayAction action = parse("{\"type\":\"TAKE\",\"cards\":[]}", DEFENDER_MOVES);
        assertNotNull(action);
        assertEquals(AutoPlayAction.Type.TAKE, action.type());
        assertNull(action.cardCode());
    }

    @Test
    void infersDefenseTargetFromDefensePlan() throws IOException {
        AutoPlayAction action = parse(
                "{\"type\":\"DEFEND\",\"cards\":[\"KH\"],\"defensePlan\":\"Beat 7H with KH\"}",
                DEFENDER_MOVES);
        assertNotNull(action);
        assertEquals(AutoPlayAction.Type.DEFEND, action.type());
        assertEquals("7H", action.attackCardCode());
        assertEquals("KH", action.cardCode());
    }

    @Test
    void ignoresThoughtPartsAndParsesFinalAnswer() throws IOException {
        String response = objectMapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of(
                        "content", Map.of(
                                "parts", List.of(
                                        Map.of("text", "Let me think about trumps...", "thought", true),
                                        Map.of("text", "{\"type\":\"ATTACK\",\"cardCode\":\"7S\"}")
                                )
                        )
                ))
        ));
        AutoPlayAction action = engine.parseResponse(response, "CODE", "player", ATTACKER_MOVES);
        assertNotNull(action);
        assertEquals(AutoPlayAction.Type.ATTACK, action.type());
        assertEquals("7S", action.cardCode());
    }

    @Test
    void returnsNullForUnparseableResponse() throws IOException {
        AutoPlayAction action = parse("I would attack with something low.", ATTACKER_MOVES);
        assertNull(action);
    }

    private AutoPlayAction parse(String modelText, ViewerLegalMoves legalMoves) throws IOException {
        String response = objectMapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of(
                        "content", Map.of(
                                "parts", List.of(Map.of("text", modelText))
                        )
                ))
        ));
        return engine.parseResponse(response, "CODE", "player", legalMoves);
    }
}
