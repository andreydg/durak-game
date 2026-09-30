package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.AttackEntry;
import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.Player;
import com.example.durakgame.model.Suit;
import com.example.durakgame.model.ViewerLegalMoves;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Builds the model prompt. Everything that does not change between turns (rules, strategy, answer
 * format) is a constant prefix, byte for byte, and all per-turn data comes last as one JSON object,
 * so Gemini's implicit prompt caching can reuse the prefix.
 *
 * <p>The per-turn state holds only what a human in the bot's seat can see. Seats are labelled
 * relative to the bot ("you", then P2, P3, P4 in turn order); player names and ids are never sent,
 * which also keeps player-chosen names from being injected into the prompt.
 */
final class GeminiPrompt {
    static final String SELF = "you";

    static final String SYSTEM_INSTRUCTION = """
            You are a strong Durak player. You play transferable ("perevodnoy") Durak with throw-ins, using only what \
            a player in your seat can see.

            Cards: a 36-card deck, ranks 6 7 8 9 10 J Q K A in four suits. Card codes are rank then suit letter: \
            C clubs, D diamonds, H hearts, S spades (for example 10D, QS, 6H). Each player starts with 6 cards. The \
            last card of the talon lies face up; its suit is trump.

            Seats: you are "you". The other seats are P2, P3 and P4 in turn order (only the seats listed in the game \
            state exist): play passes from you to P2, then to P3, then to P4, and back to you. Players who have no \
            cards left once the talon is empty are out and are skipped.

            1. The bout
            - Each bout has one lead attacker and one defender: the next active opponent after the lead attacker in \
            turn order.
            - Only the lead attacker plays the first card of a bout. After that, every player on the attacking side \
            (everyone except the defender and, in team games, the defender's partner) may throw in more cards at any \
            time and in any order; nobody waits for a turn.
            - A thrown-in card must match the rank of a card already on the table (attack or defence card).
            - There is no fixed six-card limit. A bout can never hold more attack cards than the defender had when \
            they became the defender: undefended attacks may never outnumber the cards left in the defender's hand.
            - Beating: a higher card of the same suit, or any trump against a non-trump. A trump can only be beaten \
            by a higher trump.

            2. Defending
            - Beat: answer each undefended attack card with its own beating card, in any order.
            - Transfer: before any attack card of the bout has been beaten, the defender may instead play a card of \
            the same rank as the attack cards. The transferring player becomes the lead attacker and the next active \
            opponent after them must defend against every card on the table. Allowed only if that player holds at \
            least as many cards as there will be attack cards after the transfer.
            - Take: while any attack is undefended, the defender may take instead. The attacking side may then keep \
            throwing in matching cards until the table holds as many attack cards as the defender had at the start \
            of the bout, and the defender picks up every card on the table, including their own defence cards.

            3. Ending a bout
            - When all attacks are beaten, or the defender is taking, the bout ends once every active player on the \
            attacking side has passed (END_ROUND). Any new card on the table cancels the earlier passes.
            - Successful defence: the table is discarded and leaves the game; the defender leads the next bout.
            - Take: the defender keeps the cards and is skipped; the next active player after them leads.
            - Then every hand is refilled to 6 from the talon in turn order, starting with the lead attacker.

            4. Goal
            - Two or three players: everyone plays alone. Once the talon is empty, a player who runs out of cards is \
            out and safe. The last player still holding cards is the durak and loses. If the last players run out at \
            the same time, the game is a draw.
            - Four players: two teams, partners sit opposite each other (you and P3 against P2 and P4). Partners never \
            attack each other. Once the talon is empty, players who run out are out; as soon as every player still \
            holding cards belongs to one team, that team loses. If everyone runs out at the same time, it is a draw.

            5. Strategy
            - While the talon still has cards: shed low non-trumps, keep trumps and aces, and prefer taking a couple \
            of low cards over spending a high trump on them.
            - Transferring with a low card is often better than beating: you shed a card and the next player faces \
            the whole table.
            - Never give a defender who is taking anything but junk (low non-trumps): no trumps, no aces.
            - Once the talon is empty, every card counts: keep throwing in what the defender must answer, avoid \
            taking, and lead cards nobody can beat any more.
            - Count cards: discarded cards are gone for good; cards a seat picked up stay in that hand until played. \
            Never assume other hands or the order of the talon.
            - Team games: help your partner run out and put pressure on the opponents.
            """;

    private static final String USER_PREFIX_BEFORE_BUDGET = """
            Choose the next move for the seat "you" from the game state at the end of this message.

            Answer with exactly one JSON object and nothing else:
            {"strategy":"one short sentence","type":"ATTACK|DEFEND|TRANSFER|TAKE|END_ROUND","cardCode":"card or null","attackCardCode":"attack card or null","defensePlan":[{"attackCardCode":"7H","cardCode":"KH"}]}

            Choose only from legalMoves:
            - ATTACK: cardCode from legalMoves.attackableCardCodes (lead or throw in one card).
            - DEFEND: attackCardCode is an undefended attack card, cardCode is one of legalMoves.defensesByAttackCard[attackCardCode].
            - TRANSFER: cardCode from legalMoves.transferableCardCodes.
            - TAKE: only when legalMoves.canTake is true.
            - END_ROUND (pass: stop attacking or throwing in): only when legalMoves.canEndRound is true.
            Write card codes exactly as they appear in the game state.

            One action per answer:
            - Play a single card per answer, even when you plan to throw in several; you will be asked again.
            - With several undefended attacks, first check that every one of them can be beaten with a different card. If you defend, put the complete assignment for every undefended attack in defensePlan (including this answer's pair) and return one of its pairs as the action. If they cannot all be beaten, take (or transfer) instead of wasting cards on a partial defence.
            - Leave defensePlan empty unless you defend.
            - If you take, keep strategy short and do not list the cards you could have defended with.
            """;

    private static final String USER_PREFIX_AFTER_BUDGET = """

            Game state fields:
            - seats: every seat in turn order starting with you. role is attacker (leads this bout), defender, thrower (may throw in), partner_of_defender (team games: sits this bout out) or out (no cards left, talon empty); relation is self, partner or opponent. handSize is exact for you; for other seats it is exact below 6 and "6+" otherwise, as the table shows it.
            - talonEmpty and onlyTrumpCardLeftInTalon describe the talon as everyone sees it; the exact count is not shown.
            - boutsCompleted: bouts finished so far in this game.
            - takingCardsInProgress and takeLimit: the defender is taking; the attacking side may throw in until the table holds takeLimit attack cards ("6+" means at least six; legalMoves shows whether another card still fits).
            - publicCardMemory.discarded: cards that have left the game. publicCardMemory.pickedUpBySeat: cards a seat picked up from the table and has not played since.

            Game state:
            """;

    private final ObjectMapper objectMapper;
    private final boolean publicCardMemoryEnabled;
    private final String userPrefix;

    /**
     * @param reasoningBudgetInstruction optional static instruction (for Gemma models); empty for none
     */
    GeminiPrompt(ObjectMapper objectMapper, boolean publicCardMemoryEnabled, String reasoningBudgetInstruction) {
        this.objectMapper = objectMapper;
        this.publicCardMemoryEnabled = publicCardMemoryEnabled;
        String budget = reasoningBudgetInstruction == null || reasoningBudgetInstruction.isBlank()
                ? ""
                : reasoningBudgetInstruction.strip() + "\n";
        this.userPrefix = USER_PREFIX_BEFORE_BUDGET + budget + USER_PREFIX_AFTER_BUDGET;
    }

    String systemInstruction() {
        return SYSTEM_INSTRUCTION;
    }

    /** The constant part of the user message; the per-turn JSON follows it directly. */
    String userPrefix() {
        return userPrefix;
    }

    String userPrompt(Game game, String playerId, ViewerLegalMoves legalMoves) throws JsonProcessingException {
        return userPrefix + objectMapper.writeValueAsString(gameState(game, playerId, legalMoves));
    }

    /** Per-turn state as seen from {@code playerId}'s seat. Package-private for tests. */
    Map<String, Object> gameState(Game game, String playerId, ViewerLegalMoves legalMoves) {
        List<Player> players = game.getPlayers();
        Map<String, String> labels = seatLabels(players, playerId);
        boolean talonEmpty = game.getTalonSize() == 0;
        boolean teams = players.stream().anyMatch(player -> player.getTeam() != null);
        Player self = players.stream().filter(player -> Objects.equals(player.getId(), playerId)).findFirst()
                .orElse(null);
        Player defender = players.stream()
                .filter(player -> Objects.equals(player.getId(), game.getDefenderPlayerId()))
                .findFirst()
                .orElse(null);

        List<Map<String, Object>> seats = new ArrayList<>();
        for (Player player : inTurnOrder(players, playerId)) {
            Map<String, Object> seat = new LinkedHashMap<>();
            seat.put("seat", labels.get(player.getId()));
            seat.put("relation", relation(self, player));
            seat.put("role", role(game, player, defender, teams, talonEmpty));
            // Own count is exact; other seats are shown as the table shows them (exact below 6, else "6+").
            seat.put("handSize", player == self ? player.handSize() : VisibleHandSize.describe(player.handSize()));
            seats.add(seat);
        }

        List<Map<String, Object>> table = new ArrayList<>();
        for (AttackEntry entry : game.getTable()) {
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("attackCard", entry.getAttackCard().code());
            pair.put("defenseCard", entry.getDefenseCard() == null ? null : entry.getDefenseCard().code());
            pair.put("playedBy", labels.getOrDefault(entry.getAttackerId(), "?"));
            table.add(pair);
        }

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("mode", teams ? "teams" : "free_for_all");
        state.put("boutsCompleted", game.getBoutsCompleted());
        state.put("trumpSuit", game.getTrumpSuit() == null ? null : game.getTrumpSuit().code());
        state.put("trumpCard", game.getTrumpCard() == null ? null : game.getTrumpCard().code());
        state.put("talonEmpty", talonEmpty);
        state.put("onlyTrumpCardLeftInTalon", game.getTalonSize() == 1);
        state.put("ownHand", self == null ? List.of() : sortedCodes(self.getHand(), game.getTrumpSuit()));
        state.put("seats", seats);
        state.put("table", table);
        state.put("takingCardsInProgress", game.isTakingCardsInProgress());
        // The limit is the defender's hand at the start of the bout, so it is capped the same way.
        state.put("takeLimit", VisibleHandSize.describe(game.getTakeLimit()));
        if (publicCardMemoryEnabled) {
            Map<String, Object> memory = new LinkedHashMap<>();
            memory.put("discarded", game.getDiscardedCards().stream().map(Card::code).toList());
            Map<String, List<String>> pickedUp = new LinkedHashMap<>();
            game.getKnownCardsByPlayer().forEach((knownPlayerId, cards) -> {
                String label = labels.get(knownPlayerId);
                if (label != null && !cards.isEmpty()) {
                    pickedUp.put(label, cards.stream().map(Card::code).toList());
                }
            });
            memory.put("pickedUpBySeat", pickedUp);
            state.put("publicCardMemory", memory);
        }
        state.put("legalMoves", legalMoves(legalMoves));
        return state;
    }

    /** "you" for the bot, then P2, P3, P4 following the engine's turn order (decreasing seat index). */
    private static Map<String, String> seatLabels(List<Player> players, String playerId) {
        Map<String, String> labels = new HashMap<>();
        List<Player> ordered = inTurnOrder(players, playerId);
        for (int i = 0; i < ordered.size(); i++) {
            labels.put(ordered.get(i).getId(), i == 0 ? SELF : "P" + (i + 1));
        }
        return labels;
    }

    private static List<Player> inTurnOrder(List<Player> players, String playerId) {
        int self = 0;
        for (int i = 0; i < players.size(); i++) {
            if (Objects.equals(players.get(i).getId(), playerId)) {
                self = i;
            }
        }
        List<Player> ordered = new ArrayList<>(players.size());
        for (int step = 0; step < players.size(); step++) {
            ordered.add(players.get(Math.floorMod(self - step, players.size())));
        }
        return ordered;
    }

    private static String relation(Player self, Player player) {
        if (self == null || Objects.equals(self.getId(), player.getId())) {
            return "self";
        }
        return self.getTeam() != null && Objects.equals(self.getTeam(), player.getTeam()) ? "partner" : "opponent";
    }

    private static String role(Game game, Player player, Player defender, boolean teams, boolean talonEmpty) {
        if (talonEmpty && player.handSize() == 0) {
            return "out";
        }
        if (Objects.equals(player.getId(), game.getDefenderPlayerId())) {
            return "defender";
        }
        if (Objects.equals(player.getId(), game.getAttackerPlayerId())) {
            return "attacker";
        }
        if (teams && defender != null && defender.getTeam() != null
                && Objects.equals(defender.getTeam(), player.getTeam())) {
            return "partner_of_defender";
        }
        return "thrower";
    }

    /** Grouped by suit with trumps last, low to high, so the model reads its hand at a glance. */
    private static List<String> sortedCodes(List<Card> hand, Suit trump) {
        return hand.stream()
                .sorted(Comparator
                        .comparing((Card card) -> card.suit() == trump)
                        .thenComparing(card -> card.suit().ordinal())
                        .thenComparingInt(card -> card.rank().strength()))
                .map(Card::code)
                .toList();
    }

    private static Map<String, Object> legalMoves(ViewerLegalMoves moves) {
        Map<String, Object> legal = new LinkedHashMap<>();
        legal.put("canAttack", moves.canAttack());
        legal.put("canDefend", moves.canDefend());
        legal.put("canTransfer", moves.canTransfer());
        legal.put("canTake", moves.canTake());
        legal.put("canEndRound", moves.canEndRound());
        legal.put("attackableCardCodes", moves.attackableCardCodes());
        legal.put("transferableCardCodes", moves.transferableCardCodes());
        legal.put("defensesByAttackCard", new TreeMap<>(moves.defensesByAttackCard()));
        return legal;
    }
}
