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
        roleTags,
        playerTeam,
        onAttackingSide,
        gameResult,
        lobbyRowsHtml
    };
});
