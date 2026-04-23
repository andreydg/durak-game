package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.Player;
import com.example.durakgame.model.ViewerLegalMoves;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@Primary
public class QwenAutoPlayDecisionEngine implements AutoPlayDecisionEngine {
    private final HeuristicAutoPlayDecisionEngine fallback;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final Duration timeout;

    public QwenAutoPlayDecisionEngine(
            HeuristicAutoPlayDecisionEngine fallback,
            @Value("${autoplay.qwen.enabled:false}") boolean enabled,
            @Value("${autoplay.qwen.api-key:}") String apiKey,
            @Value("${autoplay.qwen.model:qwen3.5-omni-flash}") String model,
            @Value("${autoplay.qwen.base-url:https://dashscope-intl.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${autoplay.request-timeout-ms:3500}") long timeoutMs
    ) {
        this.fallback = fallback;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.baseUrl = baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Override
    public AutoPlayAction choose(Game game, String playerId, ViewerLegalMoves legalMoves) {
        if (!enabled || apiKey.isBlank()) {
            return fallback.choose(game, playerId, legalMoves);
        }
        try {
            String requestBody = buildRequest(game, playerId, legalMoves);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return fallback.choose(game, playerId, legalMoves);
            }
            AutoPlayAction fromModel = parseResponse(response.body());
            if (fromModel == null) {
                return fallback.choose(game, playerId, legalMoves);
            }
            return fromModel;
        } catch (Exception ignored) {
            return fallback.choose(game, playerId, legalMoves);
        }
    }

    private String buildRequest(Game game, String playerId, ViewerLegalMoves legalMoves) throws IOException {
        Player me = game.getPlayers().stream()
                .filter(p -> Objects.equals(p.getId(), playerId))
                .findFirst()
                .orElse(null);
        List<String> hand = me == null ? List.of() : me.getHand().stream().map(c -> c.code()).toList();
        List<Map<String, String>> table = new ArrayList<>();
        game.getTable().forEach(entry -> table.add(Map.of(
                "attackCard", entry.getAttackCard().code(),
                "defenseCard", entry.getDefenseCard() == null ? "" : entry.getDefenseCard().code()
        )));

        String system = """
                You are an autoplay engine for Durak.
                Return exactly one JSON object and no extra text.
                Schema: {"type":"ATTACK|DEFEND|TRANSFER|TAKE|END_ROUND","cardCode":"optional","attackCardCode":"optional"}.
                Only return legal moves from the provided legalMoves object.
                Prefer fast and safe legal play; do not invent cards.
                """;
        Map<String, Object> userPayload = Map.of(
                "playerId", playerId,
                "hand", hand,
                "attackerPlayerId", game.getAttackerPlayerId(),
                "defenderPlayerId", game.getDefenderPlayerId(),
                "takingCardsInProgress", game.isTakingCardsInProgress(),
                "table", table,
                "legalMoves", legalMoves
        );

        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", objectMapper.writeValueAsString(userPayload))
                )
        );
        return objectMapper.writeValueAsString(body);
    }

    private AutoPlayAction parseResponse(String raw) throws IOException {
        JsonNode root = objectMapper.readTree(raw);
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
        if (contentNode.isMissingNode() || contentNode.isNull()) {
            return null;
        }
        String content = contentNode.asText();
        int left = content.indexOf('{');
        int right = content.lastIndexOf('}');
        if (left < 0 || right <= left) {
            return null;
        }
        JsonNode action = objectMapper.readTree(content.substring(left, right + 1));
        String type = action.path("type").asText("");
        String cardCode = action.path("cardCode").asText(null);
        String attackCardCode = action.path("attackCardCode").asText(null);
        if (type.isBlank()) {
            return null;
        }
        try {
            return new AutoPlayAction(AutoPlayAction.Type.valueOf(type), cardCode, attackCardCode);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
