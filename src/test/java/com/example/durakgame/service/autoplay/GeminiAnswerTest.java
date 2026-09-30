package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.ViewerLegalMoves;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GeminiAnswerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final ViewerLegalMoves ATTACKER_MOVES = new ViewerLegalMoves(
            false, true, false, false, false, false,
            List.of("6D", "7S", "10H"), List.of(), Map.of());

    private static final ViewerLegalMoves THROW_IN_MOVES = new ViewerLegalMoves(
            false, true, false, false, false, true,
            List.of("7C"), List.of(), Map.of());

    private static final ViewerLegalMoves DEFENDER_MOVES = new ViewerLegalMoves(
            false, false, true, true, true, false,
            List.of(), List.of("7S"), Map.of("7H", List.of("KH", "8S")));

    private static final ViewerLegalMoves TWO_ATTACK_DEFENDER_MOVES = new ViewerLegalMoves(
            false, false, true, false, true, false,
            List.of(), List.of(), Map.of("7H", List.of("KH", "8S"), "9C", List.of("QC", "8S")));

    private AutoPlayAction resolve(String json, ViewerLegalMoves moves) throws Exception {
        Set<String> undefended = Set.copyOf(moves.defensesByAttackCard().keySet());
        return new GeminiAnswer(objectMapper.readTree(json)).resolve(moves, undefended);
    }

    @Test
    void acceptsATypedLegalAttack() throws Exception {
        assertEquals(AutoPlayAction.attack("6D"), resolve("{\"type\":\"ATTACK\",\"cardCode\":\"6D\"}", ATTACKER_MOVES));
    }

    @Test
    void normalizesCardCodesCaseInsensitively() throws Exception {
        assertEquals(AutoPlayAction.attack("6D"), resolve("{\"type\":\"attack\",\"cardCode\":\" 6d \"}", ATTACKER_MOVES));
        assertEquals(AutoPlayAction.attack("10H"), resolve("{\"type\":\"ATTACK\",\"cardCode\":\"10h\"}", ATTACKER_MOVES));
        assertEquals(AutoPlayAction.attack("10H"), resolve("{\"type\":\"ATTACK\",\"cardCode\":\"10♥\"}", ATTACKER_MOVES));
        assertEquals(AutoPlayAction.defend("7H", "KH"),
                resolve("{\"type\":\"DEFEND\",\"attackCardCode\":\"7h\",\"cardCode\":\"kh\"}", DEFENDER_MOVES));
    }

    @Test
    void infersTypeFromTheActionLabelAndTheCardFromTheCardsArray() throws Exception {
        assertEquals(AutoPlayAction.attack("6D"),
                resolve("{\"strategy\":\"open low\",\"action\":\"Attack\",\"cards\":[\"6D\"]}", ATTACKER_MOVES));
    }

    @Test
    void treatsADefendersSameRankAttackAsATransfer() throws Exception {
        assertEquals(AutoPlayAction.transfer("7S"),
                resolve("{\"type\":\"ATTACK\",\"cards\":[\"7S\"],\"action\":\"Attack\"}", DEFENDER_MOVES));
    }

    @Test
    void takesOnlyWhenTakingIsLegal() throws Exception {
        assertEquals(AutoPlayAction.take(), resolve("{\"type\":\"TAKE\",\"cards\":[]}", DEFENDER_MOVES));
        assertNull(resolve("{\"type\":\"TAKE\"}", ATTACKER_MOVES));
    }

    @Test
    void endsTheRoundOnlyWhenThatIsLegal() throws Exception {
        assertEquals(AutoPlayAction.endRound(), resolve("{\"type\":\"END_ROUND\"}", THROW_IN_MOVES));
        assertEquals(AutoPlayAction.endRound(), resolve("{\"action\":\"Pass\"}", THROW_IN_MOVES));
        assertNull(resolve("{\"type\":\"END_ROUND\"}", ATTACKER_MOVES));
    }

    @Test
    void rejectsCardsThatAreNotLegal() throws Exception {
        assertNull(resolve("{\"type\":\"ATTACK\",\"cardCode\":\"AS\"}", ATTACKER_MOVES), "not in the legal list");
        assertNull(resolve("{\"type\":\"ATTACK\",\"cardCode\":\"6X\"}", ATTACKER_MOVES), "not a card");
        assertNull(resolve("{\"type\":\"ATTACK\"}", ATTACKER_MOVES), "no card at all");
        assertNull(resolve("{\"type\":\"TRANSFER\",\"cardCode\":\"6D\"}", ATTACKER_MOVES), "cannot transfer");
        assertNull(resolve("{\"type\":\"DEFEND\",\"attackCardCode\":\"7H\",\"cardCode\":\"QC\"}",
                TWO_ATTACK_DEFENDER_MOVES), "QC does not beat 7H and is not silently re-targeted");
        assertNull(resolve("{\"type\":\"FOLD\"}", ATTACKER_MOVES), "unknown type");
    }

    @Test
    void infersTheDefenceTargetFromTheDefencePlan() throws Exception {
        assertEquals(AutoPlayAction.defend("7H", "KH"),
                resolve("{\"type\":\"DEFEND\",\"cards\":[\"KH\"],\"defensePlan\":\"Beat 7H with KH\"}", DEFENDER_MOVES));
        assertEquals(AutoPlayAction.defend("9C", "8S"),
                resolve("{\"type\":\"DEFEND\",\"cardCode\":\"8S\",\"defensePlan\":"
                        + "[{\"attackCardCode\":\"9C\",\"cardCode\":\"8S\"},{\"attackCardCode\":\"7H\",\"cardCode\":\"KH\"}]}",
                        TWO_ATTACK_DEFENDER_MOVES));
    }

    @Test
    void withoutAPlanAMissingTargetGoesToTheMostConstrainedAttack() throws Exception {
        ViewerLegalMoves moves = new ViewerLegalMoves(
                false, false, true, false, true, false,
                List.of(), List.of(), Map.of("7H", List.of("KH", "8S", "9H"), "9C", List.of("8S")));

        assertEquals(AutoPlayAction.defend("9C", "8S"), resolve("{\"type\":\"DEFEND\",\"cardCode\":\"8S\"}", moves));
    }

    @Test
    void readsStructuredAndFreeTextDefencePlans() throws Exception {
        Set<String> undefended = Set.of("7H", "9C");
        GeminiAnswer structured = new GeminiAnswer(objectMapper.readTree(
                "{\"defensePlan\":[{\"attackCardCode\":\"7h\",\"cardCode\":\"KH\"},{\"attack\":\"9C\",\"defense\":\"8S\"}]}"));
        assertEquals(List.of(new GeminiAnswer.DefencePair("7H", "KH"), new GeminiAnswer.DefencePair("9C", "8S")),
                structured.defencePlan(undefended));

        GeminiAnswer map = new GeminiAnswer(objectMapper.readTree("{\"defensePlan\":{\"7H\":\"KH\",\"9C\":\"8S\"}}"));
        assertEquals(structured.defencePlan(undefended), map.defencePlan(undefended));

        GeminiAnswer text = new GeminiAnswer(objectMapper.readTree(
                "{\"defensePlan\":\"KH beats 7H, then 9C with 8S as planned\"}"));
        assertEquals(structured.defencePlan(undefended), text.defencePlan(undefended));
    }

    @Test
    void describesTheRawAnswerForLogsWithoutUnsafeCharacters() throws Exception {
        GeminiAnswer answer = new GeminiAnswer(objectMapper.readTree(
                "{\"type\":\"DEFEND\\nFAKE=1\",\"cardCode\":\"6c\",\"attackCardCode\":\"7 h\"}"));

        assertEquals("DEFENDFAKE1/6c/7h", answer.describe());
    }
}
