package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.ViewerLegalMoves;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A model's answer object and its validation against the legal moves the bot was offered. Card
 * codes are normalized ({@code " 6c"} is {@code 6C}) and a few well-defined omissions are
 * inferred (a defender's same-rank "attack" is a transfer, a missing defence target comes from the
 * model's own plan); anything else that is not legal is rejected rather than repaired.
 */
final class GeminiAnswer {
    /** Upper-case card codes in free text; the look-arounds keep words such as "was" from matching. */
    private static final Pattern CARD_IN_TEXT = Pattern.compile("(?<![A-Za-z0-9])(10|[6-9JQKA])([CDHS])(?![A-Za-z0-9])");
    private static final int LOG_FIELD_LIMIT = 12;

    /** One (attack, defence) assignment from the model's defence plan, as canonical card codes. */
    record DefencePair(String attackCardCode, String cardCode) {
    }

    private final JsonNode node;

    GeminiAnswer(JsonNode node) {
        this.node = node;
    }

    /**
     * The legal action this answer describes, or {@code null} when it names no legal move.
     *
     * @param undefendedAttacks canonical codes of the attacks currently waiting for a defence; used
     *                          to tell attack from defence cards in a free-text plan
     */
    AutoPlayAction resolve(ViewerLegalMoves moves, Set<String> undefendedAttacks) {
        AutoPlayAction.Type type = type();
        if (type == null) {
            return null;
        }
        String card = AutoPlayLegality.canonicalCardCode(text("cardCode"));
        List<String> listedCards = listedCards();
        if (card == null && !listedCards.isEmpty()) {
            card = listedCards.getFirst();
        }
        return switch (type) {
            case TAKE -> moves.canTake() ? AutoPlayAction.take() : null;
            case END_ROUND -> moves.canEndRound() ? AutoPlayAction.endRound() : null;
            case ATTACK -> {
                if (card != null && moves.canAttack() && moves.attackableCardCodes().contains(card)) {
                    yield AutoPlayAction.attack(card);
                }
                /* A defender playing a same-rank card is transferring, whatever the label says. */
                if (card != null && !moves.canAttack() && moves.canTransfer()
                        && moves.transferableCardCodes().contains(card)) {
                    yield AutoPlayAction.transfer(card);
                }
                yield null;
            }
            case TRANSFER -> card != null && moves.canTransfer() && moves.transferableCardCodes().contains(card)
                    ? AutoPlayAction.transfer(card)
                    : null;
            case DEFEND -> resolveDefence(moves, undefendedAttacks);
        };
    }

    private AutoPlayAction resolveDefence(ViewerLegalMoves moves, Set<String> undefendedAttacks) {
        if (!moves.canDefend()) {
            return null;
        }
        String attack = AutoPlayLegality.canonicalCardCode(text("attackCardCode"));
        String card = AutoPlayLegality.canonicalCardCode(text("cardCode"));
        List<String> candidates = card != null ? List.of(card) : listedCards();
        List<DefencePair> plan = defencePlan(undefendedAttacks);
        if (attack != null) {
            for (String candidate : candidates) {
                if (legalDefence(moves, attack, candidate)) {
                    return AutoPlayAction.defend(attack, candidate);
                }
            }
            if (candidates.isEmpty()) {
                /* Target without a card: use the model's own plan for that attack. */
                for (DefencePair pair : plan) {
                    if (pair.attackCardCode().equals(attack) && legalDefence(moves, attack, pair.cardCode())) {
                        return AutoPlayAction.defend(attack, pair.cardCode());
                    }
                }
            }
            return null;
        }
        for (String candidate : candidates) {
            for (DefencePair pair : plan) {
                if (pair.cardCode().equals(candidate) && legalDefence(moves, pair.attackCardCode(), candidate)) {
                    return AutoPlayAction.defend(pair.attackCardCode(), candidate);
                }
            }
            /* No target anywhere: the card goes to the most constrained attack it can beat. */
            String inferred = moves.defensesByAttackCard().entrySet().stream()
                    .filter(entry -> entry.getValue().contains(candidate))
                    .min(Comparator.comparingInt((Map.Entry<String, List<String>> entry) -> entry.getValue().size())
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (inferred != null) {
                return AutoPlayAction.defend(inferred, candidate);
            }
        }
        return null;
    }

    private static boolean legalDefence(ViewerLegalMoves moves, String attack, String card) {
        return attack != null && card != null && AutoPlayLegality.defencesFor(moves, attack).contains(card);
    }

    /**
     * The model's defence plan as oriented pairs. Accepts the requested
     * {@code [{"attackCardCode":"7H","cardCode":"KH"}]} shape, an {@code {"7H":"KH"}} map, or free text
     * such as {@code "Beat 7H with KH; 8H with AS"}; free-text pairs are oriented by which card is an
     * undefended attack. Pairs that cannot be read are skipped.
     */
    List<DefencePair> defencePlan(Set<String> undefendedAttacks) {
        JsonNode plan = node.path("defensePlan");
        if (plan.isMissingNode() || plan.isNull()) {
            plan = node.path("defencePlan");
        }
        Set<DefencePair> pairs = new LinkedHashSet<>();
        if (plan.isArray()) {
            for (JsonNode entry : plan) {
                if (entry.isObject()) {
                    String attack = firstCard(entry, "attackCardCode", "attackCard", "attack");
                    String defence = firstCard(entry, "cardCode", "defenseCardCode", "defenceCardCode",
                            "defenseCard", "defenceCard", "defense", "defence", "card");
                    if (attack != null && defence != null) {
                        pairs.add(new DefencePair(attack, defence));
                    }
                } else if (entry.isTextual()) {
                    pairs.addAll(pairsFromText(entry.asText(), undefendedAttacks));
                }
            }
        } else if (plan.isObject()) {
            plan.properties().forEach(field -> {
                String attack = AutoPlayLegality.canonicalCardCode(field.getKey());
                String defence = field.getValue().isTextual()
                        ? AutoPlayLegality.canonicalCardCode(field.getValue().asText())
                        : null;
                if (attack != null && defence != null) {
                    pairs.add(new DefencePair(attack, defence));
                }
            });
        } else if (plan.isTextual()) {
            pairs.addAll(pairsFromText(plan.asText(), undefendedAttacks));
        }
        return List.copyOf(pairs);
    }

    private static List<DefencePair> pairsFromText(String text, Set<String> undefendedAttacks) {
        List<DefencePair> pairs = new ArrayList<>();
        for (String segment : text.split("[;,\\n]|\\band\\b|\\bthen\\b")) {
            List<String> codes = new ArrayList<>();
            Matcher matcher = CARD_IN_TEXT.matcher(segment);
            while (matcher.find()) {
                codes.add(matcher.group(1) + matcher.group(2));
            }
            if (codes.size() != 2) {
                continue;
            }
            boolean firstIsAttack = undefendedAttacks.contains(codes.get(0));
            boolean secondIsAttack = undefendedAttacks.contains(codes.get(1));
            if (firstIsAttack && !secondIsAttack) {
                pairs.add(new DefencePair(codes.get(0), codes.get(1)));
            } else if (secondIsAttack && !firstIsAttack) {
                pairs.add(new DefencePair(codes.get(1), codes.get(0)));
            }
        }
        return pairs;
    }

    String strategy() {
        String strategy = text("strategy");
        return strategy == null ? "" : strategy;
    }

    /** What the model said, reduced to safe single-token log fields (type/card/attackCard). */
    String describe() {
        String type = text("type");
        if (type == null) {
            type = text("action");
        }
        String card = text("cardCode");
        if (card == null) {
            List<String> listed = rawListedCards();
            card = listed.isEmpty() ? null : listed.getFirst();
        }
        return safe(type) + "/" + safe(card) + "/" + safe(text("attackCardCode"));
    }

    private AutoPlayAction.Type type() {
        AutoPlayAction.Type type = parseType(text("type"));
        return type != null ? type : parseType(text("action"));
    }

    private static AutoPlayAction.Type parseType(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (normalized) {
            case "ATTACK", "THROW_IN" -> AutoPlayAction.Type.ATTACK;
            case "DEFEND", "BEAT", "DEFENSE", "DEFENCE" -> AutoPlayAction.Type.DEFEND;
            case "TRANSFER" -> AutoPlayAction.Type.TRANSFER;
            case "TAKE" -> AutoPlayAction.Type.TAKE;
            case "END_ROUND", "PASS", "ENDROUND" -> AutoPlayAction.Type.END_ROUND;
            default -> null;
        };
    }

    private List<String> listedCards() {
        List<String> cards = new ArrayList<>();
        for (String raw : rawListedCards()) {
            String canonical = AutoPlayLegality.canonicalCardCode(raw);
            if (canonical != null) {
                cards.add(canonical);
            }
        }
        return cards;
    }

    private List<String> rawListedCards() {
        List<String> cards = new ArrayList<>();
        JsonNode listed = node.path("cards");
        if (listed.isArray()) {
            for (JsonNode card : listed) {
                if (card.isTextual() && !card.asText().isBlank()) {
                    cards.add(card.asText());
                }
            }
        }
        return cards;
    }

    private static String firstCard(JsonNode entry, String... fields) {
        for (String field : fields) {
            JsonNode value = entry.path(field);
            if (value.isTextual()) {
                String canonical = AutoPlayLegality.canonicalCardCode(value.asText());
                if (canonical != null) {
                    return canonical;
                }
            }
        }
        return null;
    }

    private String text(String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String safe(String value) {
        if (value == null) {
            return "-";
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9_]", "");
        if (cleaned.isEmpty()) {
            return "?";
        }
        return cleaned.length() > LOG_FIELD_LIMIT ? cleaned.substring(0, LOG_FIELD_LIMIT) : cleaned;
    }
}
