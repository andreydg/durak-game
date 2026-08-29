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

    function escapeHtml(text) {
        const d = document.createElement("div");
        d.textContent = text == null ? "" : String(text);
        return d.innerHTML;
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
                    <span class="muted">${(r.playerNames || []).map(escapeHtml).join(", ")} · ${r.playerCount}/${r.maxPlayers} players</span>
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
        shouldReplaceRefreshTimer,
        lobbyRefreshDelayMs,
        escapeHtml,
        roleTags,
        playerTeam,
        onAttackingSide,
        gameResult,
        lobbyRowsHtml
    };
});
