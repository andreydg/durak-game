/*
 * Pure presentation/logic helpers for the Durak UI, factored out of app.js so they can be
 * unit-tested in isolation. Loaded as a classic script before app.js (exposing window.DurakLogic)
 * and consumed as a CommonJS module by the Vitest suite.
 */
(function (root, factory) {
    const api = factory();
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    }
    if (typeof window !== "undefined") {
        window.DurakLogic = api;
    }
})(typeof self !== "undefined" ? self : this, function () {
    const SUIT_GLYPHS = { S: "♠", H: "♥", D: "♦", C: "♣" };
    const SUIT_GLYPHS_FULL = { HEARTS: "♥", DIAMONDS: "♦", CLUBS: "♣", SPADES: "♠" };

    function prettyCard(code) {
        if (!code) return "-";
        const s = code.slice(-1);
        const r = code.slice(0, -1);
        return `${r}${SUIT_GLYPHS[s] || s}`;
    }

    function sortCardCodesByRank(codes) {
        const rankOrder = { "6": 0, "7": 1, "8": 2, "9": 3, "10": 4, "J": 5, "Q": 6, "K": 7, "A": 8 };
        const suitOrder = { C: 0, D: 1, H: 2, S: 3 };
        return [...(codes || [])].sort((a, b) => {
            const rankA = String(a || "").slice(0, -1).toUpperCase();
            const rankB = String(b || "").slice(0, -1).toUpperCase();
            const suitA = String(a || "").slice(-1).toUpperCase();
            const suitB = String(b || "").slice(-1).toUpperCase();
            const rankCmp = (rankOrder[rankA] ?? 999) - (rankOrder[rankB] ?? 999);
            if (rankCmp !== 0) return rankCmp;
            return (suitOrder[suitA] ?? 999) - (suitOrder[suitB] ?? 999);
        });
    }

    function trumpSuitGlyph(suitCode) {
        if (!suitCode) return "";
        const u = String(suitCode).toUpperCase();
        if (SUIT_GLYPHS[u]) return SUIT_GLYPHS[u];
        return SUIT_GLYPHS_FULL[u] || suitCode || "";
    }

    function displayStatus(rawStatus) {
        switch (rawStatus) {
            case "LOBBY":
                return "Lobby";
            case "IN_PROGRESS":
                return "In progress";
            case "FINISHED":
                return "Finished";
            default:
                return String(rawStatus || "")
                    .toLowerCase()
                    .replace(/_/g, " ")
                    .replace(/\b\w/g, ch => ch.toUpperCase());
        }
    }

    function roomCodeFromSearch(search) {
        const code = new URLSearchParams(search || "").get("room");
        const normalized = String(code || "").trim().toUpperCase();
        return /^[A-Z0-9]{6}$/.test(normalized) ? normalized : "";
    }

    function buildInviteUrl(origin, roomCode) {
        const code = String(roomCode || "").trim().toUpperCase();
        if (!/^[A-Z0-9]{6}$/.test(code)) return "";
        const url = new URL("/", origin);
        url.searchParams.set("room", code);
        return url.toString();
    }

    /** Remove only room query parameters while preserving every other raw query byte. */
    function searchWithoutRoomParam(search) {
        const raw = String(search || "");
        const query = raw.startsWith("?") ? raw.slice(1) : raw;
        if (!query) return "";
        const kept = query.split("&").filter(part => {
            const rawKey = part.split("=", 1)[0];
            try {
                return decodeURIComponent(rawKey.replace(/\+/g, " ")) !== "room";
            } catch {
                return true;
            }
        });
        return kept.length ? `?${kept.join("&")}` : "";
    }

    /** Bounded exponential reconnect delay with ±20% jitter. */
    function reconnectDelayMs(attempt, randomValue = Math.random()) {
        const safeAttempt = Math.max(0, Math.min(10, Number(attempt) || 0));
        const base = Math.min(30_000, 1_000 * (2 ** safeAttempt));
        const random = Math.max(0, Math.min(1, Number(randomValue) || 0));
        return Math.min(30_000, Math.round(base * (0.8 + random * 0.4)));
    }

    /** HTTP refresh is only a fallback/health check while realtime game updates are healthy. */
    function gameRefreshDelayMs(webSocketConnected, visibilityState = "visible") {
        if (visibilityState === "hidden") return null;
        return webSocketConnected ? 30_000 : 3_000;
    }

    /** Prevent a delayed read from replacing newer state returned by an action. */
    function shouldAcceptGameVersion(currentVersion, incomingVersion) {
        if (currentVersion == null || incomingVersion == null) return true;
        const current = Number(currentVersion);
        const incoming = Number(incomingVersion);
        if (!Number.isFinite(current) || !Number.isFinite(incoming)) return true;
        return incoming >= current;
    }

    /**
     * Whether an incoming game snapshot (refresh or action response) may replace the current one.
     * Snapshots of another room always replace; within a room, an older version never does.
     */
    function shouldApplySnapshot(current, incoming) {
        if (!incoming || typeof incoming !== "object" || !Array.isArray(incoming.players) || !incoming.code) {
            return false;
        }
        if (!current || current.code !== incoming.code) return true;
        return shouldAcceptGameVersion(current.version, incoming.version);
    }

    /**
     * Decodes a response body without trusting it: empty bodies (such as /leave) are null, and a
     * body that is not declared as JSON (a proxy's HTML error page, say) is reported as unreadable.
     */
    function parseJsonBody(contentType, text) {
        const body = String(text == null ? "" : text).trim();
        if (!body) return { ok: true, value: null };
        if (!/[/+]json\b/i.test(String(contentType || ""))) return { ok: false, value: null };
        try {
            return { ok: true, value: JSON.parse(body) };
        } catch {
            return { ok: false, value: null };
        }
    }

    /** Message for a failed request: the server's own message when it sent one. */
    function apiErrorMessage(status, payload) {
        const serverMessage = payload && typeof payload.message === "string" ? payload.message.trim() : "";
        if (serverMessage) return serverMessage;
        const code = Number(status) || 0;
        if (code === 0) return "Could not reach the server. Check your connection and try again.";
        if (code === 403) return "You are not authorized to act as this player.";
        if (code === 404) return "Game not found";
        if (code === 410) return "Room expired due to inactivity.";
        if (code === 429) return "Too many requests. Wait a moment and try again.";
        if (code >= 500) return "The server is unavailable right now. Please try again.";
        return "Request failed. Please try again.";
    }

    /**
     * What a failed request means for the saved seat: 403 is a seat this browser can no longer
     * use, 404/410 a room that is gone, anything else (network, 409, 429, 5xx) is transient.
     */
    function sessionErrorKind(status) {
        const code = Number(status) || 0;
        if (code === 403) return "seat-invalid";
        if (code === 404 || code === 410) return "room-gone";
        return "transient";
    }

    /**
     * Detects a game view that the server answered as a stranger: it still returns the public
     * view when the token is wrong, just without the viewer's hand and moves. Returns null when
     * the seat looks usable, "not-seated" when the viewer is not at the table, or "unauthorized".
     */
    function seatProblem(game, viewerId) {
        if (!game || !Array.isArray(game.players) || !viewerId) return null;
        const me = game.players.find(player => player.id === viewerId);
        if (!me) return "not-seated";
        const handHidden = Number(me.handSize) > 0 && !(Array.isArray(me.hand) && me.hand.length > 0);
        if (game.status === "IN_PROGRESS" && handHidden) return "unauthorized";
        const hostCouldStart = game.status === "LOBBY"
            && game.hostPlayerId === viewerId
            && game.players.length >= 2;
        if (hostCouldStart && !(game.legalMoves && game.legalMoves.canStart)) return "unauthorized";
        return null;
    }

    /**
     * The card that should take focus when `lost` leaves the hand: itself if still held, else its
     * nearest surviving neighbour in the previous order (right first, so focus keeps its slot),
     * else the card now at that position. Null when the hand is empty.
     */
    function neighborCardCode(previousCodes, lost, currentCodes) {
        const next = Array.isArray(currentCodes) ? currentCodes : [];
        if (!next.length) return null;
        if (lost && next.includes(lost)) return lost;
        const prev = Array.isArray(previousCodes) ? previousCodes : [];
        const index = prev.indexOf(lost);
        if (index < 0) return next[0];
        for (let distance = 1; distance < prev.length; distance++) {
            for (const candidate of [prev[index + distance], prev[index - distance]]) {
                if (candidate && next.includes(candidate)) return candidate;
            }
        }
        return next[Math.min(index, next.length - 1)];
    }

    /** Controls whose use is about the hand: when they lose focus, the hand is the natural next stop. */
    const HAND_ACTION_IDS = ["attackBtn", "defendBtn", "transferBtn", "takeBtn", "endRoundBtn", "defendTargetSelect"];

    /**
     * Decides where keyboard focus goes after a re-render removed, hid or disabled the focused
     * element. `lost` is {kind: "card", code} or {kind: "control", id}; `availableControls` lists
     * safe fallback control ids in preference order (never destructive ones such as Leave or Take).
     * Returns {kind: "card", code} | {kind: "control", id} | {kind: "heading"}.
     */
    function focusRecoveryTarget({
        lost,
        handBefore = [],
        handAfter = [],
        lastPlayedCard = null,
        availableControls = [],
        stillAvailable = false
    } = {}) {
        const firstControl = availableControls.length ? { kind: "control", id: availableControls[0] } : null;
        const firstCard = handAfter.length ? { kind: "card", code: handAfter[0] } : null;
        if (!lost) return { kind: "heading" };
        if (lost.kind === "card") {
            const code = neighborCardCode(handBefore, lost.code, handAfter);
            return code ? { kind: "card", code } : firstControl || { kind: "heading" };
        }
        if (stillAvailable) return { kind: "control", id: lost.id };
        if (HAND_ACTION_IDS.includes(lost.id)) {
            const code = lastPlayedCard ? neighborCardCode(handBefore, lastPlayedCard, handAfter) : null;
            if (code) return { kind: "card", code };
            return firstCard || firstControl || { kind: "heading" };
        }
        return firstControl || firstCard || { kind: "heading" };
    }

    /** Which screen is showing; a change of view moves focus to that view's heading. */
    function viewKey({ reconnecting = false, hasSession = false, status = null } = {}) {
        if (!hasSession) return reconnecting ? "reconnecting" : "lobby";
        if (status === "IN_PROGRESS") return "table";
        if (status === "FINISHED") return "result";
        return "room";
    }

    /** Preserve a pending prompt refresh unless the new deadline is earlier or explicitly replaces it. */
    function shouldReplaceRefreshTimer(existingDueAt, requestedDueAt, replaceExisting = false) {
        if (replaceExisting) return true;
        const existing = Number(existingDueAt) || 0;
        const requested = Number(requestedDueAt) || 0;
        return existing <= 0 || requested < existing;
    }

    /** Lobby events drive normal updates; failed fallback reads back off to protect the store. */
    function lobbyRefreshDelayMs(webSocketConnected, failureCount = 0, visibilityState = "visible") {
        if (visibilityState === "hidden") return null;
        if (webSocketConnected) return 60_000;
        const failures = Math.max(0, Math.min(3, Number(failureCount) || 0));
        return Math.min(30_000, 4_000 * (2 ** failures));
    }

    const HTML_ESCAPES = { "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;" };

    /** Safe for element text and for quoted attribute values alike. */
    function escapeHtml(text) {
        return (text == null ? "" : String(text)).replace(/[&<>"']/g, ch => HTML_ESCAPES[ch]);
    }

    /** Room codes are six characters from the server's alphabet (GameService.CODE_ALPHABET). */
    const ROOM_CODE_PATTERN = /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$/;

    /** Normalizes typed input to a room code, or returns "" when it cannot be one. */
    function normalizeRoomCode(input) {
        const code = String(input == null ? "" : input).trim().toUpperCase();
        return ROOM_CODE_PATTERN.test(code) ? code : "";
    }

    const CARD_CODE_PATTERN = /^(?:[6-9]|10|[JQKA])[CDHS]$/;

    /** True for the 36 card codes the server deals ("6C" .. "AS"). */
    function isCardCode(code) {
        return typeof code === "string" && CARD_CODE_PATTERN.test(code);
    }

    const SUIT_NAMES = { C: "clubs", D: "diamonds", H: "hearts", S: "spades" };
    const FULL_SUIT_CODES = { CLUBS: "C", DIAMONDS: "D", HEARTS: "H", SPADES: "S" };
    const RANK_NAMES = { J: "jack", Q: "queen", K: "king", A: "ace" };

    /** "spades" for "S" or "SPADES"; "" when unknown. */
    function suitName(suit) {
        const code = String(suit || "").toUpperCase();
        return SUIT_NAMES[FULL_SUIT_CODES[code] || code] || "";
    }

    /** Spoken name of a card code: "6C" -> "6 of clubs", "QH" -> "queen of hearts". */
    function cardName(code) {
        const text = String(code || "");
        const suit = SUIT_NAMES[text.slice(-1).toUpperCase()];
        const rank = text.slice(0, -1).toUpperCase();
        if (!suit || !rank) return text;
        return `${RANK_NAMES[rank] || rank} of ${suit}`;
    }

    /** "a", "a and b", "a, b and c". */
    function listPhrase(items) {
        if (items.length <= 1) return items.join("");
        return `${items.slice(0, -1).join(", ")} and ${items[items.length - 1]}`;
    }

    /**
     * What sighted players can tell from an opponent's fan: it draws at most six backs, so the
     * exact count is only visible below six. Never reveals more than the picture does.
     */
    function fanCountLabel(handSize) {
        const count = Math.max(0, Math.floor(Number(handSize) || 0));
        if (count >= 6) return "6 or more cards";
        if (count === 0) return "no cards";
        return count === 1 ? "1 card" : `${count} cards`;
    }

    /** Words for roleTags' emoji: "attacker, team 1", "defender, taking the cards", "the durak". */
    function roleDescription(player, game) {
        const parts = [];
        if (game.status === "IN_PROGRESS") {
            if (player.id === game.attackerPlayerId) parts.push("attacker");
            if (player.id === game.defenderPlayerId) parts.push("defender");
            if (game.takingCardsInProgress && player.id === game.takingPlayerId) parts.push("taking the cards");
        } else if (game.status === "FINISHED" && player.id === game.loserPlayerId) {
            parts.push("the durak");
        }
        if (player.team !== null && player.team !== undefined) parts.push(`team ${player.team}`);
        return parts.join(", ");
    }

    /** Accessible description of one table pair. */
    function tablePairLabel(pair) {
        const attack = cardName(pair && pair.attackCard);
        return pair && pair.defenseCard
            ? `${attack}, beaten by ${cardName(pair.defenseCard)}`
            : `${attack}, not beaten yet`;
    }

    function gameOverSentence(game, viewerId) {
        if (!game.loserPlayerId) return "Game over. It's a draw: nobody is the durak.";
        const players = game.players || [];
        const viewer = players.find(player => player.id === viewerId) || null;
        const loser = players.find(player => player.id === game.loserPlayerId) || null;
        const loserName = loser ? loser.name : (game.loserPlayerName || "A player");
        const loserTeam = loser ? loser.team : game.loserTeam;
        if (loserTeam !== null && loserTeam !== undefined && viewer && viewer.team !== null && viewer.team !== undefined) {
            return viewer.team === loserTeam
                ? "Game over. Your team is the durak."
                : `Game over. Your team wins. ${loserName}'s team is the durak.`;
        }
        if (game.loserPlayerId === viewerId) return "Game over. You are the durak.";
        return `Game over. ${loserName} is the durak.`;
    }

    /**
     * Short announcements for a screen reader describing what changed between two snapshots of
     * the same game, from the viewer's point of view ("Elektronik attacks with 7 of hearts",
     * "You beat 7 of hearts with 9 of hearts", "Bout over", "Your turn to defend", ...).
     * Only mentions what a sighted player can see: opponents' hand sizes are never spelled out.
     */
    function describeTransition(prev, next, viewerId) {
        if (!prev || !next || prev.code !== next.code || !Array.isArray(next.players)) return [];
        const out = [];
        const prevPlayers = Array.isArray(prev.players) ? prev.players : [];
        const known = [...next.players, ...prevPlayers.filter(p => !next.players.some(q => q.id === p.id))];
        const nameOf = id => (known.find(p => p.id === id) || {}).name || "A player";
        const says = (id, youText, otherText) => (id && id === viewerId ? `You ${youText}` : `${nameOf(id)} ${otherText}`);

        const prevIds = new Set(prevPlayers.map(p => p.id));
        const nextIds = new Set(next.players.map(p => p.id));
        for (const p of next.players) {
            if (!prevIds.has(p.id) && p.id !== viewerId) out.push(`${p.name} joined.`);
        }
        for (const p of prevPlayers) {
            if (!nextIds.has(p.id) && p.id !== viewerId) out.push(`${p.name} left.`);
        }

        const wasPlaying = prev.status === "IN_PROGRESS";
        const playing = next.status === "IN_PROGRESS";
        if (next.status === "FINISHED" && prev.status !== "FINISHED") {
            out.push(gameOverSentence(next, viewerId));
            return out;
        }
        if (wasPlaying && !playing) {
            out.push("The game was stopped and the room is back in the lobby.");
            return out;
        }
        if (!playing) return out;

        if (!wasPlaying) {
            const trump = suitName(next.trumpSuit);
            out.push(trump ? `New game. Trump is ${trump}.` : "New game.");
        }

        const prevTable = wasPlaying && Array.isArray(prev.table) ? prev.table : [];
        const nextTable = Array.isArray(next.table) ? next.table : [];
        const sameBout = prevTable.length > 0 && nextTable.length > 0
            && prevTable[0].attackCard === nextTable[0].attackCard;
        const boutEnded = prevTable.length > 0 && !sameBout;

        if (boutEnded) {
            out.push("Bout over.");
            const before = (prevPlayers.find(p => p.id === viewerId) || {}).hand || [];
            const after = (next.players.find(p => p.id === viewerId) || {}).hand || [];
            const tableCards = new Set(prevTable.flatMap(pair => [pair.attackCard, pair.defenseCard]).filter(Boolean));
            const gained = after.filter(card => !before.includes(card));
            const pickedUp = gained.filter(card => tableCards.has(card));
            const drawn = gained.filter(card => !tableCards.has(card));
            if (pickedUp.length) out.push(`You pick up ${pickedUp.length === 1 ? "1 card" : `${pickedUp.length} cards`}.`);
            if (drawn.length) out.push(`You draw ${listPhrase(drawn.map(cardName))}.`);
        }

        const earlier = sameBout ? prevTable : [];
        for (let i = 0; i < earlier.length; i++) {
            const was = earlier[i];
            const now = nextTable[i];
            if (now && !was.defenseCard && now.defenseCard) {
                out.push(`${says(next.defenderPlayerId, "beat", "beats")} ${cardName(now.attackCard)} with ${cardName(now.defenseCard)}.`);
            }
        }
        let newAttacks = 0;
        for (const pair of nextTable.slice(earlier.length)) {
            newAttacks++;
            const transferred = sameBout && pair.attackerId === prev.defenderPlayerId
                && next.defenderPlayerId !== prev.defenderPlayerId;
            if (transferred) {
                out.push(`${says(pair.attackerId, "transfer", "transfers")} with ${cardName(pair.attackCard)}.`);
            } else if (next.takingCardsInProgress && sameBout) {
                out.push(`${says(pair.attackerId, "throw in", "throws in")} ${cardName(pair.attackCard)}.`);
            } else {
                out.push(`${says(pair.attackerId, "attack", "attacks")} with ${cardName(pair.attackCard)}.`);
            }
            if (pair.defenseCard) {
                out.push(`${says(next.defenderPlayerId, "beat", "beats")} ${cardName(pair.attackCard)} with ${cardName(pair.defenseCard)}.`);
            }
        }

        if (next.takingCardsInProgress && !(sameBout && prev.takingCardsInProgress)) {
            out.push(`${says(next.takingPlayerId, "take", "takes")} the cards.`);
        }

        const moves = next.legalMoves || {};
        const undefended = nextTable.some(pair => !pair.defenseCard);
        if (next.defenderPlayerId === viewerId && undefended && !next.takingCardsInProgress
            && (newAttacks > 0 || prev.defenderPlayerId !== viewerId)) {
            out.push("Your turn to defend.");
        } else if (nextTable.length === 0 && (boutEnded || !wasPlaying || prev.attackerPlayerId !== next.attackerPlayerId)) {
            out.push(next.attackerPlayerId === viewerId
                ? "Your turn to attack."
                : `${nameOf(next.attackerPlayerId)} attacks next.`);
        } else if (moves.canEndRound && !(prev.legalMoves || {}).canEndRound && !next.takingCardsInProgress) {
            out.push("All attacks are beaten. Press End round or add a matching card.");
        }
        return out;
    }

    function roleTags(player, game) {
        const t = [];
        if (game.status === "IN_PROGRESS") {
            if (player.id === game.attackerPlayerId) t.push("⚔️");
            if (player.id === game.defenderPlayerId) t.push("🛡️");
            if (game.takingCardsInProgress && player.id === game.takingPlayerId) t.push("⇩");
        } else if (game.status === "FINISHED" && player.id === game.loserPlayerId) {
            t.push("🤡");
        }
        if (player.team !== null && player.team !== undefined) t.push(`team ${player.team}`);
        return t.join(" + ");
    }

    function playerTeam(game, playerId) {
        const p = game?.players?.find(x => x.id === playerId);
        if (!p || p.team === undefined || p.team === null) return null;
        return p.team;
    }

    /** In 4p teams, attacking side is opposite team; otherwise all non-defenders. */
    function onAttackingSide(game, viewerId) {
        const defId = game?.defenderPlayerId;
        if (!game || !defId || !viewerId) return false;
        if (viewerId === defId) return false;
        if (game.players?.length !== 4) return true;
        const dt = playerTeam(game, defId);
        const vt = playerTeam(game, viewerId);
        return dt != null && vt != null && vt !== dt;
    }

    function gameResult(game, viewerId) {
        if (!game || game.status !== "FINISHED") return null;
        const players = game.players || [];
        const viewer = players.find(player => player.id === viewerId) || null;
        if (!game.loserPlayerId) {
            return {
                outcome: "draw",
                icon: "🤝",
                title: "Nobody is the durak",
                summary: "The game ended with no player holding cards."
            };
        }
        const seatedLoser = players.find(player => player.id === game.loserPlayerId) || null;
        const loser = seatedLoser || (game.loserPlayerName ? {
            id: game.loserPlayerId,
            name: game.loserPlayerName,
            team: game.loserTeam ?? null
        } : null);
        if (!loser) {
            return {
                outcome: "spectator",
                icon: "🃏",
                title: "The durak left the table",
                summary: "The completed result is still preserved."
            };
        }
        const teamGame = loser.team != null;
        if (teamGame && viewer?.team != null) {
            const viewerLost = viewer.team === loser.team;
            return {
                outcome: viewerLost ? "loss" : "win",
                icon: viewerLost ? "🤡" : "🏆",
                title: viewerLost ? "Your team is the durak" : "Your team won!",
                summary: `${loser.name}’s team was left holding cards.`
            };
        }
        if (viewer?.id === loser.id) {
            return {
                outcome: "loss",
                icon: "🤡",
                title: "You’re the durak",
                summary: "You were the last player holding cards."
            };
        }
        if (viewer) {
            return {
                outcome: "win",
                icon: "🏆",
                title: "You won!",
                summary: `${loser.name} was the last player holding cards.`
            };
        }
        return {
            outcome: "spectator",
            icon: "🃏",
            title: `${loser.name} is the durak`,
            summary: `${loser.name} was the last player holding cards.`
        };
    }

    function lobbyRowsHtml(rows, interactive, currentCode) {
        const cur = (currentCode || "").toUpperCase();
        if (!rows.length) return "";
        return `<ul class="lobby-list">${rows.map(r => {
            const isYours = cur && String(r.code).toUpperCase() === cur;
            const rowClass = "lobby-list-item" + (isYours ? " lobby-list-item--yours" : "");
            const actionCol = interactive
                ? `<button type="button" class="btn-primary lobby-list-join" data-code="${escapeHtml(r.code)}">Join</button>`
                : (isYours
                    ? `<span class="lobby-this-room">This room</span>`
                    : `<span class="muted" style="font-size:0.88rem;">In lobby</span>`);
            return `
            <li class="${rowClass}">
                <div class="lobby-list-meta">
                    <span class="lobby-list-code">${escapeHtml(r.code)}</span>
                    <span class="muted">${(r.playerNames || []).map(escapeHtml).join(", ")} · ${escapeHtml(r.playerCount)}/${escapeHtml(r.maxPlayers)} players</span>
                </div>
                ${actionCol}
            </li>`;
        }).join("")}</ul>`;
    }

    return {
        prettyCard,
        sortCardCodesByRank,
        trumpSuitGlyph,
        displayStatus,
        roomCodeFromSearch,
        buildInviteUrl,
        searchWithoutRoomParam,
        reconnectDelayMs,
        gameRefreshDelayMs,
        shouldAcceptGameVersion,
        shouldApplySnapshot,
        parseJsonBody,
        apiErrorMessage,
        sessionErrorKind,
        seatProblem,
        neighborCardCode,
        focusRecoveryTarget,
        viewKey,
        shouldReplaceRefreshTimer,
        lobbyRefreshDelayMs,
        escapeHtml,
        normalizeRoomCode,
        isCardCode,
        suitName,
        cardName,
        fanCountLabel,
        roleDescription,
        tablePairLabel,
        describeTransition,
        roleTags,
        playerTeam,
        onAttackingSide,
        gameResult,
        lobbyRowsHtml
    };
});
