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
        escapeHtml,
        roleTags,
        playerTeam,
        onAttackingSide,
        lobbyRowsHtml
    };
});
