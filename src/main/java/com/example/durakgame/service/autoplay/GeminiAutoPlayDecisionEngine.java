package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.Card;
import com.example.durakgame.model.Player;
import com.example.durakgame.model.ViewerLegalMoves;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
@Primary
public class GeminiAutoPlayDecisionEngine implements AutoPlayDecisionEngine {
    private static final Logger log = LoggerFactory.getLogger(GeminiAutoPlayDecisionEngine.class);

    private static final long MAX_CONNECT_TIMEOUT_MS = 5000;

    private final HeuristicAutoPlayDecisionEngine fallback;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final String thinkingLevel;
    private final Duration timeout;
    private final long reasoningBudgetSeconds;
    private final boolean publicCardMemoryEnabled;
    private final boolean jsonModeSupported;
    private final boolean systemInstructionSupported;
    private final boolean thinkingConfigSupported;
    private final boolean promptReasoningBudgetUsed;

    public GeminiAutoPlayDecisionEngine(
            HeuristicAutoPlayDecisionEngine fallback,
            ObjectMapper objectMapper,
            @Value("${autoplay.gemini.enabled:true}") boolean enabled,
            @Value("${autoplay.gemini.api-key:}") String apiKey,
            @Value("${autoplay.gemini.model:gemini-3.1-flash-lite-preview}") String model,
            @Value("${autoplay.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
            @Value("${autoplay.gemini.thinking-level:HIGH}") String thinkingLevel,
            @Value("${autoplay.gemini.public-card-memory-enabled:true}") boolean publicCardMemoryEnabled,
            @Value("${autoplay.gemini.reasoning-budget-seconds:30}") long reasoningBudgetSeconds,
            @Value("${autoplay.gemini.json-mode:auto}") String jsonModeFlag,
            @Value("${autoplay.gemini.system-instruction:auto}") String systemInstructionFlag,
            @Value("${autoplay.gemini.thinking-config:auto}") String thinkingConfigFlag,
            @Value("${autoplay.gemini.prompt-reasoning-budget:auto}") String promptReasoningBudgetFlag,
            @Value("${autoplay.request-timeout-ms:30000}") long timeoutMs
    ) {
        this.fallback = fallback;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(MAX_CONNECT_TIMEOUT_MS, timeoutMs)))
                .build();
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.baseUrl = baseUrl;
        this.thinkingLevel = thinkingLevel;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.reasoningBudgetSeconds = Math.max(1L, reasoningBudgetSeconds);
        this.publicCardMemoryEnabled = publicCardMemoryEnabled;
        /* Model capabilities: default derived from the model family, overridable per property (auto|true|false). */
        this.jsonModeSupported = resolveCapability(jsonModeFlag, !model.startsWith("gemma-3-"));
        this.systemInstructionSupported = resolveCapability(systemInstructionFlag, !model.startsWith("gemma-3-"));
        this.thinkingConfigSupported = resolveCapability(thinkingConfigFlag, model.startsWith("gemini-3"));
        this.promptReasoningBudgetUsed = resolveCapability(promptReasoningBudgetFlag, model.startsWith("gemma-"));
    }

    private static boolean resolveCapability(String flag, boolean autoDefault) {
        String normalized = flag == null ? "" : flag.trim();
        if (normalized.isEmpty() || normalized.equalsIgnoreCase("auto")) {
            return autoDefault;
        }
        return Boolean.parseBoolean(normalized);
    }

    @Override
    public AutoPlayAction choose(Game game, String playerId, ViewerLegalMoves legalMoves) {
        if (!enabled || apiKey.isBlank()) {
            log.info("autoplay_gemini_fallback code={} playerId={} reason=disabled_or_missing_api_key",
                    game.getCode(), playerId);
            return fallback.choose(game, playerId, legalMoves);
        }
        AutoPlayAction fromPrimary = chooseWithModel(game, playerId, legalMoves);
        if (fromPrimary != null) {
            return fromPrimary;
        }
        log.info("autoplay_gemini_fallback code={} playerId={} reason=primary_model_failed",
                game.getCode(), playerId);
        return fallback.choose(game, playerId, legalMoves);
    }

    private AutoPlayAction chooseWithModel(Game game, String playerId, ViewerLegalMoves legalMoves) {
        try {
            log.info("autoplay_gemini_request code={} playerId={} model={} timeoutMs={}",
                    game.getCode(), playerId, model, timeout.toMillis());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint()))
                    .timeout(timeout)
                    .header("x-goog-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequest(game, playerId, legalMoves)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("autoplay_gemini_response code={} playerId={} model={} status={} responseBytes={}",
                    game.getCode(), playerId, model, response.statusCode(), response.body().length());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.info("autoplay_gemini_failed code={} playerId={} model={} reason=http_status status={} body={}",
                        game.getCode(), playerId, model, response.statusCode(), humanizeResponseBody(response.body()));
                return null;
            }
            AutoPlayAction fromModel = parseResponse(response.body(), game.getCode(), playerId, legalMoves);
            if (fromModel == null) {
                log.info("autoplay_gemini_failed code={} playerId={} model={} reason=unparseable_response",
                        game.getCode(), playerId, model);
                return null;
            }
            return fromModel;
        } catch (Exception ex) {
            log.info("autoplay_gemini_failed code={} playerId={} model={} reason=exception message={}",
                    game.getCode(), playerId, model, ex.getMessage());
            return null;
        }
    }

    private String endpoint() {
        return baseUrl + "/models/" + model + ":generateContent";
    }

    private String buildRequest(Game game, String playerId, ViewerLegalMoves legalMoves) throws IOException {
        String prompt = buildPrompt(game, playerId, legalMoves);
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", 0);
        if (jsonModeSupported) {
            generationConfig.put("responseMimeType", "application/json");
        }
        if (thinkingConfigSupported) {
            generationConfig.put("thinkingConfig", Map.of("thinkingLevel", thinkingLevel));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (systemInstructionSupported) {
            body.put("systemInstruction", Map.of(
                    "parts", List.of(Map.of("text", systemInstruction()))
            ));
            body.put("contents", List.of(Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", prompt))
            )));
        } else {
            body.put("contents", List.of(Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", systemInstruction() + "\n\n" + prompt))
            )));
        }
        body.put("generationConfig", generationConfig);
        return objectMapper.writeValueAsString(body);
    }

    private String buildPrompt(Game game, String playerId, ViewerLegalMoves legalMoves) throws IOException {
        Player me = game.getPlayers().stream()
                .filter(p -> Objects.equals(p.getId(), playerId))
                .findFirst()
                .orElse(null);
        List<String> hand = me == null ? List.of() : me.getHand().stream().map(c -> c.code()).toList();
        List<Map<String, Object>> players = new ArrayList<>();
        for (Player player : game.getPlayers()) {
            Map<String, Object> visiblePlayer = new LinkedHashMap<>();
            visiblePlayer.put("id", player.getId());
            visiblePlayer.put("name", player.getName());
            visiblePlayer.put("bot", player.isBot());
            visiblePlayer.put("team", player.getTeam());
            visiblePlayer.put("handSize", player.handSize());
            visiblePlayer.put("self", Objects.equals(player.getId(), playerId));
            players.add(visiblePlayer);
        }
        List<Map<String, String>> table = new ArrayList<>();
        game.getTable().forEach(entry -> table.add(Map.of(
                "attackCard", entry.getAttackCard().code(),
                "defenseCard", entry.getDefenseCard() == null ? "" : entry.getDefenseCard().code()
        )));
        Map<String, Object> gameState = new LinkedHashMap<>();
        gameState.put("playerId", playerId);
        gameState.put("ownHand", hand);
        gameState.put("playersInSeatOrder", players);
        gameState.put("playerCount", game.getPlayers().size());
        gameState.put("attackerPlayerId", game.getAttackerPlayerId());
        gameState.put("defenderPlayerId", game.getDefenderPlayerId());
        gameState.put("trumpSuit", game.getTrumpSuit() == null ? null : game.getTrumpSuit().code());
        gameState.put("trumpCard", game.getTrumpCard() == null ? null : game.getTrumpCard().code());
        gameState.put("onlyTrumpCardLeftInTalon", game.getTalonSize() == 1);
        gameState.put("takingCardsInProgress", game.isTakingCardsInProgress());
        gameState.put("takeLimit", game.getTakeLimit());
        gameState.put("table", table);
        if (publicCardMemoryEnabled) {
            gameState.put("publicCardMemory", publicCardMemory(game));
        }
        gameState.put("legalMoves", legalMoves);
        String reasoningBudgetInstruction = reasoningBudgetInstruction();
        return """
                Choose the next move for playerId using the game state below.
                You must choose only from legalMoves.
                The game state contains only information visible to this bot as a human player:
                its own hand, seat order, public hand sizes, trump, whether only the visible trump card remains in the talon, table cards, current roles, and legal moves.
                Do not assume hidden opponent hands or hidden talon cards.
                %s
                Return exactly one JSON object and no extra text.
                JSON schema:
                {"strategy":"reasoning based on team/FFA and cards remaining","defensePlan":"optional plan for all undefended attacks","action":"Attack|Beat|Transfer|Pass|Take","cards":["6S","10D"],"type":"ATTACK|DEFEND|TRANSFER|TAKE|END_ROUND","cardCode":"optional","attackCardCode":"optional"}
                The cards field must describe only this response's single machine action. Put multi-card defense plans only in defensePlan, not cards.

                Mapping from strategy terms to JSON:
                - Attack -> ATTACK with cardCode
                - Beat -> DEFEND with attackCardCode and cardCode
                - Transfer -> TRANSFER with cardCode
                - Take -> TAKE with cards=[]
                - Pass -> END_ROUND only when canEndRound is true
                If choosing TAKE, do not reveal cards you could have used to defend in cards, defensePlan, or strategy.

                Role discipline:
                - If canAttack is false, do not return ATTACK.
                - If you are the defender and play a same-rank card from transferableCardCodes, that is TRANSFER, not ATTACK.
                - If you are the defender and play a card from defensesByAttackCard, that is DEFEND with the matching attackCardCode.

                Attack pacing:
                - Unless takingCardsInProgress is true, choose exactly one ATTACK card per response.
                - For normal attacks, set cards to a single-card list matching cardCode.
                - Playing one attack card at a time lets you observe the defender's response and improve the next decision.
                - When takingCardsInProgress is true, you may plan multiple final throw-ins in strategy, but still return only the next single ATTACK card in cardCode.

                If defending and multiple attack cards are undefended, reason holistically:
                - First determine whether all undefended attacks can be beaten with the available defense options.
                - Prefer a defense assignment that preserves trumps, aces, and flexible cards for later attacks.
                - Choose this response's single DEFEND action as the next step from that full defense plan.
                - Include the full plan in defensePlan or strategy.
                - If the whole set cannot be defended, choose TAKE instead of wasting a good card on a partial defense.
                - You may still choose TAKE for strategic reasons even if defense is possible, but then reveal no defense cards.

                Use human-like card-counting in strategy:
                - When publicCardMemory is present, use it as public table history available to all players.
                - discardedOutOfPlay cards cannot appear again and cannot be used by any player.
                - knownCardsByPlayer lists cards that were publicly picked up from the table and are known to be in that player's hand unless they have since been played.
                - Use known opponent cards to anticipate defenses, future transfers, and throw-in ranks.
                - In strategy, briefly refer to the public-card memory or card-counting inference that influenced the move.

                Populate strategy/action/cards for debug logging, but type/cardCode/attackCardCode are the authoritative machine fields.

                Current game state:
                %s
                """.formatted(reasoningBudgetInstruction, objectMapper.writeValueAsString(gameState));
    }

    private String reasoningBudgetInstruction() {
        if (!promptReasoningBudgetUsed) {
            return "";
        }
        return "Budgeted reasoning: reason for no more than " + reasoningBudgetSeconds
                + " seconds. If still uncertain, stop reasoning and return the best legal move immediately."
                + " Do not spend extra time seeking a perfect move.";
    }

    private Map<String, Object> publicCardMemory(Game game) {
        List<Map<String, Object>> knownCardsByPlayer = new ArrayList<>();
        game.getKnownCardsByPlayer().forEach((knownPlayerId, cards) -> {
            Map<String, Object> known = new LinkedHashMap<>();
            known.put("playerId", knownPlayerId);
            known.put("playerName", game.getPlayers().stream()
                    .filter(player -> Objects.equals(player.getId(), knownPlayerId))
                    .map(Player::getName)
                    .findFirst()
                    .orElse("?"));
            known.put("cards", cards.stream().map(Card::code).toList());
            knownCardsByPlayer.add(known);
        });
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("discardedOutOfPlay", game.getDiscardedCards().stream().map(Card::code).toList());
        memory.put("knownCardsByPlayer", knownCardsByPlayer);
        return memory;
    }

    private String systemInstruction() {
        return """
                Role: You are a master Durak strategist. You play with precision and aggressive card-counting.

                Card counting: Infer from visible information only. Track your own hand, the visible trump card, cards currently on the table, and any public played/discarded cards if provided. Use those observations to estimate suit pressure, remaining trump risk, and which ranks are safe to throw in. Never assume hidden opponent hands or hidden talon cards.

                1. Game Flow & Direction

                Direction: Counter-Clockwise (to your right).

                The Bout: The player to the right of the attacker is the defender.

                Throw-ins: Once the primary attacker is finished, the right to "throw in" additional cards passes to the player to the right of the defender.

                2. Multi-Mode Logic (Teams vs. FFA)

                IF 4 Players (Team Play): You and the player sitting opposite you are partners.

                STRICT RULE: You are strictly forbidden from attacking your partner. Even if your partner has decided to "Take" (Беру), you may not throw in cards to their hand. You only attack the two opponents.

                IF 2 or 3 Players (Free-For-All): Every other player is an enemy. Your only goal is to empty your hand first.

                3. Advanced Gameplay Mechanics

                Perevodnoy (Transferable):

                As a defender, you can play a card of the same rank as the attack to transfer the bout to the player on your right.

                Constraint: You cannot transfer if the next defender has fewer cards in their hand than the total cards currently on the table.

                Podkidnoy (Throw-in) & The "Take" Window:

                If a defender says "Take" (Беру), the table remains open. Other valid attackers may continue to "throw in" matching ranks until the limit is reached (6 cards or the defender's hand size).

                The defender must pick up every card played during the window.

                4. Decision Priorities

                Safety First: Protect high trumps and Aces for the end-game.

                Partner Awareness (4-player): Watch your partner's hand size. If they are low, play aggressively against the opponent to your right to ensure your partner gets to shed their last cards.

                5. Public Card Memory

                Use publicCardMemory when it is provided. Cards discarded after a defended bout are out of play and cannot be played again. Cards picked up by a player are publicly known to be in that player's hand until they are later played. Reason about these known cards when choosing attacks, defenses, transfers, and throw-ins. Refer to this card-counting memory in your strategy explanation when it affects the move, but never invent card history that is not visible or otherwise provided.

                6. Attack Pacing

                Prefer one-card attacks. Outside of the final throw-in window after a defender takes, return one ATTACK card at a time so you can observe whether the defender beats, transfers, or takes before choosing the next attack. During the take window, you may plan several throw-ins, but the machine action must still be the next single card.
                """;
    }

    /** Package-private for tests. */
    AutoPlayAction parseResponse(
            String raw,
            String gameCode,
            String playerId,
            ViewerLegalMoves legalMoves
    ) throws IOException {
        JsonNode root = objectMapper.readTree(raw);
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray()) {
            return null;
        }
        List<String> nonThoughtTexts = new ArrayList<>();
        List<String> allTexts = new ArrayList<>();
        List<String> thoughtTexts = new ArrayList<>();
        for (JsonNode part : parts) {
            JsonNode text = part.path("text");
            if (text.isTextual()) {
                String value = text.asText();
                allTexts.add(value);
                if (part.path("thought").asBoolean(false)) {
                    thoughtTexts.add(value);
                } else {
                    nonThoughtTexts.add(value);
                }
            }
        }
        logModelReasoning(gameCode, playerId, thoughtTexts);
        JsonNode action = parseActionJson(nonThoughtTexts);
        if (action == null) {
            action = parseActionJson(allTexts);
        }
        if (action == null) {
            return null;
        }
        logModelDecision(gameCode, playerId, action);
        String type = action.path("type").asText("");
        String actionLabel = action.path("action").asText("");
        String cardCode = action.path("cardCode").asText(null);
        String attackCardCode = action.path("attackCardCode").asText(null);
        if (type.isBlank()) {
            type = typeFromActionLabel(actionLabel);
        }
        if (type.isBlank()) {
            return null;
        }
        try {
            AutoPlayAction.Type actionType = AutoPlayAction.Type.valueOf(type.trim().toUpperCase(Locale.ROOT));
            if (actionType == AutoPlayAction.Type.TAKE) {
                return AutoPlayAction.take();
            }
            if ((actionType == AutoPlayAction.Type.ATTACK || actionType == AutoPlayAction.Type.TRANSFER)
                    && (cardCode == null || cardCode.isBlank())) {
                cardCode = firstCard(action);
            }
            AutoPlayAction normalized = normalizeAgainstLegalMoves(actionType, cardCode, attackCardCode, legalMoves);
            if (normalized != null) {
                return normalized;
            }
            if (actionType == AutoPlayAction.Type.DEFEND
                    && (cardCode == null || cardCode.isBlank() || attackCardCode == null || attackCardCode.isBlank())) {
                AutoPlayAction inferred = inferDefenseAction(action, legalMoves);
                if (inferred != null) {
                    return inferred;
                }
            }
            return new AutoPlayAction(actionType, cardCode, attackCardCode);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private AutoPlayAction normalizeAgainstLegalMoves(
            AutoPlayAction.Type actionType,
            String cardCode,
            String attackCardCode,
            ViewerLegalMoves legalMoves
    ) {
        if (actionType == AutoPlayAction.Type.ATTACK
                && !legalMoves.canAttack()
                && cardCode != null
                && legalMoves.canTransfer()
                && legalMoves.transferableCardCodes().contains(cardCode)) {
            return AutoPlayAction.transfer(cardCode);
        }
        if (actionType == AutoPlayAction.Type.TRANSFER
                && cardCode != null
                && legalMoves.canTransfer()
                && legalMoves.transferableCardCodes().contains(cardCode)) {
            return AutoPlayAction.transfer(cardCode);
        }
        if (actionType == AutoPlayAction.Type.ATTACK
                && cardCode != null
                && legalMoves.canAttack()
                && legalMoves.attackableCardCodes().contains(cardCode)) {
            return AutoPlayAction.attack(cardCode);
        }
        if (actionType == AutoPlayAction.Type.DEFEND
                && cardCode != null
                && attackCardCode != null
                && legalMoves.canDefend()
                && legalMoves.defensesByAttackCard().containsKey(attackCardCode)
                && legalMoves.defensesByAttackCard().get(attackCardCode).contains(cardCode)) {
            return AutoPlayAction.defend(attackCardCode, cardCode);
        }
        return null;
    }

    private String firstCard(JsonNode action) {
        JsonNode cards = action.path("cards");
        if (!cards.isArray()) {
            return null;
        }
        for (JsonNode card : cards) {
            if (card.isTextual() && !card.asText().isBlank()) {
                return card.asText();
            }
        }
        return null;
    }

    private String typeFromActionLabel(String actionLabel) {
        return switch (actionLabel == null ? "" : actionLabel.trim().toLowerCase()) {
            case "attack" -> "ATTACK";
            case "beat", "defend", "defense" -> "DEFEND";
            case "transfer" -> "TRANSFER";
            case "take" -> "TAKE";
            case "pass", "end round", "end_round" -> "END_ROUND";
            default -> "";
        };
    }

    private AutoPlayAction inferDefenseAction(JsonNode action, ViewerLegalMoves legalMoves) {
        if (!legalMoves.canDefend()) {
            return null;
        }
        List<String> candidateDefenseCards = new ArrayList<>();
        JsonNode cards = action.path("cards");
        if (cards.isArray()) {
            for (JsonNode card : cards) {
                if (card.isTextual()) {
                    candidateDefenseCards.add(card.asText());
                }
            }
        }
        String explicitCardCode = action.path("cardCode").asText("");
        if (!explicitCardCode.isBlank()) {
            candidateDefenseCards.add(0, explicitCardCode);
        }
        for (String defenseCard : candidateDefenseCards) {
            String attackCard = inferAttackForDefense(action, legalMoves, defenseCard);
            if (attackCard != null) {
                return AutoPlayAction.defend(attackCard, defenseCard);
            }
        }
        return null;
    }

    private String inferAttackForDefense(JsonNode action, ViewerLegalMoves legalMoves, String defenseCard) {
        String planText = (action.path("defensePlan").asText("") + "\n" + action.path("strategy").asText("")).toUpperCase();
        String normalizedDefense = defenseCard == null ? "" : defenseCard.toUpperCase();
        for (Map.Entry<String, List<String>> entry : legalMoves.defensesByAttackCard().entrySet()) {
            String attackCard = entry.getKey();
            if (!entry.getValue().contains(defenseCard)) {
                continue;
            }
            String normalizedAttack = attackCard.toUpperCase();
            if (planText.contains(normalizedAttack + " WITH " + normalizedDefense)
                    || planText.contains(normalizedDefense + " TO BEAT " + normalizedAttack)
                    || planText.contains("BEAT " + normalizedAttack + " WITH " + normalizedDefense)) {
                return attackCard;
            }
        }
        return legalMoves.defensesByAttackCard().entrySet().stream()
                .filter(entry -> entry.getValue().contains(defenseCard))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private void logModelReasoning(String gameCode, String playerId, List<String> thoughtTexts) {
        if (!log.isDebugEnabled() || thoughtTexts.isEmpty()) {
            return;
        }
        log.debug("""
                autoplay_gemini_reasoning code={} playerId={}
                {}""", gameCode, playerId, String.join("\n", thoughtTexts));
    }

    private JsonNode parseActionJson(List<String> texts) {
        for (int i = texts.size() - 1; i >= 0; i--) {
            JsonNode action = parseActionJson(texts.get(i));
            if (action != null) {
                return action;
            }
        }
        return null;
    }

    private JsonNode parseActionJson(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        String text = rawText.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            if (node.isObject()) {
                return node;
            }
        } catch (IOException ignored) {
            // Fall through to extracting an object from surrounding text.
        }
        int left = text.indexOf('{');
        int right = text.lastIndexOf('}');
        if (left < 0 || right <= left) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(text.substring(left, right + 1));
            return node.isObject() ? node : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private void logModelDecision(String gameCode, String playerId, JsonNode action) throws IOException {
        if (!log.isInfoEnabled()) {
            return;
        }
        String strategy = action.path("strategy").asText("");
        String defensePlan = action.path("defensePlan").asText("");
        String actionLabel = action.path("action").asText("");
        boolean taking = "TAKE".equalsIgnoreCase(action.path("type").asText(""))
                || "TAKE".equalsIgnoreCase(actionLabel);
        JsonNode cardsNode = action.path("cards");
        String cards = taking || cardsNode.isMissingNode() || cardsNode.isNull()
                ? ""
                : objectMapper.writeValueAsString(cardsNode);
        log.info("""
                autoplay_gemini_decision code={} playerId={}
                STRATEGY: {}
                DEFENSE PLAN: {}
                ACTION: {}
                CARDS: {}""", gameCode, playerId, strategy, defensePlan, actionLabel, cards);
    }

    private String humanizeResponseBody(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(objectMapper.readTree(raw));
        } catch (IOException ignored) {
            return raw.replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\u003e", ">")
                    .replace("\\u003c", "<");
        }
    }
}
