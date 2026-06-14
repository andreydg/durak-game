/* Per-tab session so a new tab can stay on the main lobby and see Open tables while another tab hosts a game. */
(function migrateLegacyLocalStorageSession() {
    const hadSs = sessionStorage.getItem("durak_game_code");
    const lc = localStorage.getItem("durak_game_code");
    const lid = localStorage.getItem("durak_player_id");
    if (!hadSs && lc && lid) {
        sessionStorage.setItem("durak_game_code", lc);
        sessionStorage.setItem("durak_player_id", lid);
    }
    localStorage.removeItem("durak_game_code");
    localStorage.removeItem("durak_player_id");
})();

/* Pure presentation helpers live in logic.js (loaded first) so they can be unit-tested. */
const {
    prettyCard,
    sortCardCodesByRank,
    trumpSuitGlyph,
    displayStatus,
    escapeHtml,
    roleTags,
    playerTeam,
    onAttackingSide,
    lobbyRowsHtml
} = window.DurakLogic;

const state = {
    gameCode: sessionStorage.getItem("durak_game_code") || "",
    playerId: sessionStorage.getItem("durak_player_id") || "",
    game: null,
    selectedHandCard: null,
    showGameplayHelp: false,
    pollTimer: null,
    ws: null,
    wsConnected: false,
    leaveNotified: false,
    suppressWsRefreshUntilMs: 0,
    lastWsHealthRefreshMs: 0,
    botThinking: {},
    botThinkingEventAt: {}
};

const lobbyView = document.getElementById("lobbyView");
const gameView = document.getElementById("gameView");
const playingArea = document.getElementById("playingArea");
const gameplayHintEl = document.getElementById("gameplayHint");
const helpToggleBtn = document.getElementById("helpToggleBtn");
const roomWaitingLine = document.getElementById("roomWaitingLine");
const messagesPanel = document.getElementById("messagesPanel");
const messages = document.getElementById("messages");
const debugUi = new URLSearchParams(window.location.search).get("debug") === "1";
if (debugUi && messagesPanel) messagesPanel.classList.remove("hidden");
const hostNameInput = document.getElementById("hostName");
const gameCodeInput = document.getElementById("gameCode");
const playerNameInput = document.getElementById("playerName");
const gameCodeLabel = document.getElementById("gameCodeLabel");
const statusLabel = document.getElementById("statusLabel");
const roleLabel = document.getElementById("roleLabel");
const deckArea = document.getElementById("deckArea");
const trumpUnderImg = document.getElementById("trumpUnderImg");
const talonStack = document.getElementById("talonStack");
const tableAttackerLabel = document.getElementById("tableAttackerLabel");
const tableDefenderLabel = document.getElementById("tableDefenderLabel");
const trumpSuitHud = document.getElementById("trumpSuitHud");
const openingLeadHud = document.getElementById("openingLeadHud");
const tableGrid = document.getElementById("tableGrid");
const seatTop1 = document.getElementById("seatTop1");
const seatTop2 = document.getElementById("seatTop2");
const seatTop3 = document.getElementById("seatTop3");
const seatLeft = document.getElementById("seatLeft");
const seatRight = document.getElementById("seatRight");
const mySeatTitle = document.getElementById("mySeatTitle");
const myRoleLine = document.getElementById("myRoleLine");
const myHand = document.getElementById("myHand");
const battleCards = document.getElementById("battleCards");
const battleTableBanner = document.getElementById("battleTableBanner");
const actionHint = document.getElementById("actionHint");
const defendTargetSelect = document.getElementById("defendTargetSelect");
const lobbyGameList = document.getElementById("lobbyGameList");
const gameOpenTablesWrap = document.getElementById("gameOpenTablesWrap");
const gameLobbyGameList = document.getElementById("gameLobbyGameList");

const startBtn = document.getElementById("startBtn");
const attackBtn = document.getElementById("attackBtn");
const defendBtn = document.getElementById("defendBtn");
const transferBtn = document.getElementById("transferBtn");
const takeBtn = document.getElementById("takeBtn");
const endRoundBtn = document.getElementById("endRoundBtn");
const addBotBtn = document.getElementById("addBotBtn");

function log(message) {
    if (!debugUi || !messages) return;
    const now = new Date().toLocaleTimeString();
    messages.textContent = `[${now}] ${message}`;
}

async function api(path, method, body) {
    const res = await fetch(path, {
        method,
        headers: {"Content-Type": "application/json"},
        body: body ? JSON.stringify(body) : undefined
    });
    const payload = await res.json();
    if (!res.ok) {
        throw new Error(payload.message || "Request failed");
    }
    return payload;
}

function notifyLeaveOnUnload() {
    if (state.leaveNotified) return;
    const code = state.gameCode;
    const playerId = state.playerId;
    if (!code || !playerId) return;
    const payload = JSON.stringify({ playerId });
    try {
        const blob = new Blob([payload], { type: "application/json" });
        const sent = navigator.sendBeacon(`/api/games/${code}/leave`, blob);
        if (sent) {
            state.leaveNotified = true;
            return;
        }
    } catch (_) {
        // Fall through to fetch keepalive.
    }
    try {
        fetch(`/api/games/${code}/leave`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: payload,
            keepalive: true
        });
        state.leaveNotified = true;
    } catch (_) {
        // Best-effort only during unload.
    }
}

function saveSession() {
    sessionStorage.setItem("durak_game_code", state.gameCode || "");
    sessionStorage.setItem("durak_player_id", state.playerId || "");
}

let lobbyListTimer = null;

async function refreshLobbyLists() {
    let rows = [];
    try {
        const res = await fetch("/api/lobbies", {cache: "no-store"});
        if (!res.ok) throw new Error("bad");
        rows = await res.json();
    } catch {
        const err = "<p class=\"lobby-list-empty muted\">Could not load open tables. Try refreshing the page.</p>";
        if (lobbyGameList) lobbyGameList.innerHTML = err;
        if (gameLobbyGameList) gameLobbyGameList.innerHTML = err;
        return;
    }

    const emptyHome = "<p class=\"lobby-list-empty muted\">No open tables yet — create one above or enter a code.</p>";
    const emptyInRoom = "<p class=\"lobby-list-empty muted\">No lobby tables returned — try Refresh or wait a moment.</p>";

    /* Always fill #lobbyGameList when data arrives — do not gate on lobbyView visibility (async fetch can race with show/hide). */
    if (lobbyGameList) {
        lobbyGameList.innerHTML = rows.length ? lobbyRowsHtml(rows, true, null) : emptyHome;
    }
    if (gameLobbyGameList) {
        const code = state.gameCode || "";
        const inRoom = gameOpenTablesWrap && !gameOpenTablesWrap.classList.contains("hidden");
        gameLobbyGameList.innerHTML = rows.length
            ? lobbyRowsHtml(rows, false, code)
            : (inRoom ? emptyInRoom : "");
    }
}

function shouldPollOpenTables() {
    if (!state.gameCode || !state.playerId || !state.game) {
        return true;
    }
    return state.game.status === "LOBBY";
}

function syncLobbyListPolling() {
    if (shouldPollOpenTables()) {
        refreshLobbyLists();
        if (!lobbyListTimer) {
            lobbyListTimer = setInterval(refreshLobbyLists, 4000);
        }
    } else {
        stopLobbyListPolling();
    }
}

function stopLobbyListPolling() {
    if (lobbyListTimer) {
        clearInterval(lobbyListTimer);
        lobbyListTimer = null;
    }
}

document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && shouldPollOpenTables()) {
        refreshLobbyLists();
    }
    if (document.visibilityState === "visible" && state.gameCode) {
        const socketOpen = state.ws && state.ws.readyState === WebSocket.OPEN;
        const socketConnecting = state.ws && state.ws.readyState === WebSocket.CONNECTING;
        if (!socketOpen && !socketConnecting) {
            connectWebSocket();
        }
        refreshGame(false);
    }
});

async function performJoin(roomCode) {
    const raw = roomCode != null && String(roomCode).trim() !== ""
        ? String(roomCode).trim()
        : gameCodeInput.value.trim();
    const code = raw.toUpperCase();
    if (!code) throw new Error("Enter a room code.");
    const joined = await api(`/api/games/${code}/join`, "POST", {playerName: playerNameInput.value.trim()});
    state.gameCode = joined.game.code;
    state.playerId = joined.playerId;
    state.game = joined.game;
    state.selectedHandCard = null;
    saveSession();
    beginPolling();
    connectWebSocket();
}

if (lobbyGameList) {
    lobbyGameList.addEventListener("click", (e) => {
        const btn = e.target.closest(".lobby-list-join");
        if (!btn || !lobbyGameList.contains(btn)) return;
        const code = btn.getAttribute("data-code");
        if (code) runAction("Join game", () => performJoin(code));
    });
}

function cardImage(code) {
    return `/cards/${code}.png`;
}

function renderSeat(el, player, game) {
    if (!player) {
        el.innerHTML = "";
        return;
    }
    const backCount = Math.min(Math.max(player.handSize, 0), 6);
    const fewFan = player.handSize < 6;
    let backs = "";
    const spreadDeg =
        backCount <= 1 ? 0 : fewFan ? 26 + (backCount - 2) * 4 : 32 + Math.min(backCount - 2, 4) * 2;
    for (let i = 0; i < backCount; i++) {
        const angle =
            backCount <= 1 ? 0 : -spreadDeg / 2 + (spreadDeg * i) / (backCount - 1);
        backs += `<span class="card-back-face card-back-face--fan" style="transform: rotate(${angle}deg); z-index: ${i + 1};" aria-hidden="true"></span>`;
    }
    const fanClass = "back-fan" + (fewFan ? " back-fan--few" : "");
    const teamClass = game.players.length === 4 && player.team !== null && player.team !== undefined
        ? ` seat-title--team${player.team}` : "";
    const tags = roleTags(player, game);
    const aiBadge = player.bot ? `<span class="ai-badge" title="Bot">🤖</span>` : "";
    const thinking = state.botThinking[player.id]
        ? `<span class="bot-thinking-inline">${escapeHtml(state.botThinking[player.id]).replace(/\\.\\.\\.$/, "")}<span class="bot-thinking-dots"></span></span>` : "";
    const tagHtml = tags ? `<span class="seat-role-inline">${escapeHtml(tags)}</span>` : "";
    el.innerHTML = `<div class="seat-title${teamClass}">${escapeHtml(player.name)}${aiBadge}${tagHtml}${thinking}</div>
        <div class="${fanClass}">${backs}</div>`;
}

function updateBattleTableBanner(game) {
    if (!battleTableBanner) return;
    if (game.status !== "IN_PROGRESS" || !game.takingCardsInProgress) {
        battleTableBanner.innerHTML = "";
        battleTableBanner.classList.add("hidden");
        return;
    }
    const taking = game.players.find(p => p.id === game.takingPlayerId);
    const takingName = taking ? taking.name : "Defender";
    const limit = Number(game.takeLimit) || 0;
    const n = (game.table || []).length;
    const sub = n < limit
        ? "Throw in matching ranks (first come), then all attackers press End round."
        : "No more throw-ins — all attackers must press End round.";
    battleTableBanner.innerHTML =
        `<span class="taking-lead-icon" aria-hidden="true">⇩</span><strong>${escapeHtml(takingName)}</strong> is taking cards — ${sub}`;
    battleTableBanner.classList.remove("hidden");
}

function renderBattle(game) {
    battleCards.innerHTML = "";
    if (!game.table || game.table.length === 0) {
        battleCards.innerHTML = "<div class='muted'>No cards on table</div>";
        return;
    }
    for (const pairData of game.table) {
        const pair = document.createElement("div");
        pair.className = "battle-pair";
        pair.dataset.attackCard = pairData.attackCard;
        pair.innerHTML = `<img class="battle-card attack" src="${cardImage(pairData.attackCard)}" title="${pairData.attackCard}" alt="${pairData.attackCard}">`;
        if (pairData.defenseCard) {
            pair.innerHTML += `<img class="battle-card defense" src="${cardImage(pairData.defenseCard)}" title="${pairData.defenseCard}" alt="${pairData.defenseCard}">`;
        }
        battleCards.appendChild(pair);
    }
}

function renderMyHand(game, me) {
    myHand.innerHTML = "";
    const hand = sortCardCodesByRank(me?.hand || []);
    for (const code of hand) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "hand-card-btn" + (state.selectedHandCard === code ? " selected" : "");
        btn.innerHTML = `<img src="${cardImage(code)}" draggable="false" title="${code}" alt="${code}">`;
        btn.draggable = true;
        btn.dataset.cardCode = code;
        btn.addEventListener("click", () => {
            state.selectedHandCard = state.selectedHandCard === code ? null : code;
            renderActionState(game);
            renderMyHand(game, me);
        });
        btn.addEventListener("dragstart", (e) => {
            btn.classList.add("dragging");
            e.dataTransfer.setData("text/plain", code);
            e.dataTransfer.effectAllowed = "move";
        });
        btn.addEventListener("dragend", () => {
            btn.classList.remove("dragging");
            clearBattleDropUi();
        });
        myHand.appendChild(btn);
    }
}

function renderActionState(game) {
    const lm = game.legalMoves || {};
    const selected = state.selectedHandCard;
    const attackable = lm.attackableCardCodes || [];
    const transferable = lm.transferableCardCodes || [];
    const defensesByAttack = lm.defensesByAttackCard || {};
    const team4 = game?.players?.length === 4;
    const tableEmpty = !game?.table || game.table.length === 0;
    const openerId = game?.attackerPlayerId;
    const iAmOpeningAttacker = Boolean(state.playerId && openerId === state.playerId);
    const iOnAttackSide = Boolean(state.playerId && onAttackingSide(game, state.playerId));

    const options = Object.keys(defensesByAttack);
    const prevAttack = defendTargetSelect.value;

    defendTargetSelect.innerHTML = "";
    for (const atk of options) {
        const opt = document.createElement("option");
        opt.value = atk;
        opt.textContent = `vs ${prettyCard(atk)} (${atk})`;
        defendTargetSelect.appendChild(opt);
    }

    const validForCard = selected
        ? options.filter(atk => (defensesByAttack[atk] || []).includes(selected))
        : [];

    let chosenAttack = "";
    if (validForCard.length > 0) {
        chosenAttack = validForCard.includes(prevAttack) ? prevAttack : validForCard[0];
    } else if (options.length > 0) {
        chosenAttack = options.includes(prevAttack) ? prevAttack : options[0];
    }
    if (chosenAttack && [...defendTargetSelect.options].some(o => o.value === chosenAttack)) {
        defendTargetSelect.value = chosenAttack;
    }

    defendTargetSelect.classList.toggle("hidden", options.length <= 1);

    const canDefendSelected = Boolean(
        selected &&
        chosenAttack &&
        (defensesByAttack[chosenAttack] || []).includes(selected)
    );

    startBtn.disabled = !lm.canStart;
    attackBtn.disabled = !(lm.canAttack && selected && attackable.includes(selected));
    transferBtn.disabled = !(lm.canTransfer && selected && transferable.includes(selected));
    defendBtn.disabled = !canDefendSelected;
    takeBtn.disabled = !lm.canTake;
    endRoundBtn.disabled = !lm.canEndRound;

    if (game.takingCardsInProgress) {
        actionHint.textContent = "See the message on the table — use buttons or drag cards.";
        return;
    }

    if (lm.canEndRound) {
        actionHint.textContent = lm.canAttack
            ? "All attacks are defended — you may add another attack (matching rank) or press End round."
            : "All attacks are defended — press End round to finish this bout.";
    } else if (!selected) {
        if (team4 && tableEmpty && iOnAttackSide && iAmOpeningAttacker) {
            actionHint.textContent = "Your turn — lead the first attack (⚔️ opening attacker).";
        } else if (team4 && tableEmpty && iOnAttackSide && !iAmOpeningAttacker) {
            const opener = game.players.find(p => p.id === openerId);
            actionHint.textContent =
                `Wait for ${opener ? opener.name : "your teammate"} to lead; then you can add matching ranks.`;
        } else {
            actionHint.textContent = "Select or drag a card";
        }
    } else {
        const hints = [];
        if (attackable.includes(selected)) hints.push("can attack");
        if (transferable.includes(selected)) hints.push("can transfer");
        if (validForCard.length > 0) hints.push(`can defend (${validForCard.map(prettyCard).join(" or ")})`);
        if (hints.length) {
            actionHint.textContent = `${selected}: ${hints.join(", ")}`;
        } else if (team4 && tableEmpty && iOnAttackSide && !iAmOpeningAttacker) {
            actionHint.textContent =
                `${selected}: wait for your teammate to lead first; then matching ranks can be added.`;
        } else {
            actionHint.textContent = `${selected}: no legal move right now`;
        }
    }
}

/**
 * Drag card to battle: transfer first, then attack, then defend.
 * For defend, optional preferredAttackCard comes from the .battle-pair you dropped on.
 */
async function playCardToTable(cardCode, preferredAttackCard) {
    const game = state.game;
    if (!game || game.status !== "IN_PROGRESS" || !cardCode) return;
    const lm = game.legalMoves || {};
    const attackable = lm.attackableCardCodes || [];
    const transferable = lm.transferableCardCodes || [];
    const defs = lm.defensesByAttackCard || {};
    const attacksYouCanBeat = Object.keys(defs).filter(atk => (defs[atk] || []).includes(cardCode));

    if (lm.canTransfer && transferable.includes(cardCode)) {
        await runAction("Transfer", async () => {
            state.game = await api(`/api/games/${state.gameCode}/transfer`, "POST", {
                playerId: state.playerId,
                card: cardCode
            });
            state.selectedHandCard = null;
        });
        return;
    }
    if (lm.canAttack && attackable.includes(cardCode)) {
        await runAction("Attack", async () => {
            state.game = await api(`/api/games/${state.gameCode}/attack`, "POST", {
                playerId: state.playerId,
                card: cardCode
            });
            state.selectedHandCard = null;
        });
        return;
    }
    if (attacksYouCanBeat.length > 0) {
        let target = "";
        if (preferredAttackCard && attacksYouCanBeat.includes(preferredAttackCard)) {
            target = preferredAttackCard;
        } else if (defendTargetSelect.value && attacksYouCanBeat.includes(defendTargetSelect.value)) {
            target = defendTargetSelect.value;
        } else {
            target = attacksYouCanBeat[0];
        }
        if (!target) {
            log("No attack to defend against.");
            return;
        }
        await runAction("Defend", async () => {
            state.game = await api(`/api/games/${state.gameCode}/defend`, "POST", {
                playerId: state.playerId,
                attackCard: target,
                defenseCard: cardCode
            });
            state.selectedHandCard = null;
        });
        return;
    }
    log(`Cannot play ${cardCode} to the table right now.`);
}

function render() {
    const game = state.game;
    const hasSession = Boolean(state.gameCode && state.playerId && game);
    lobbyView.classList.toggle("hidden", hasSession);
    gameView.classList.toggle("hidden", !hasSession);

    const showPlayingArea =
        hasSession && game && (game.status === "IN_PROGRESS" || game.status === "FINISHED");
    if (playingArea) playingArea.classList.toggle("hidden", !showPlayingArea);
    if (gameplayHintEl) gameplayHintEl.classList.toggle("hidden", !showPlayingArea || !state.showGameplayHelp);
    if (helpToggleBtn) {
        helpToggleBtn.classList.toggle("hidden", !showPlayingArea);
        helpToggleBtn.setAttribute("aria-expanded", String(showPlayingArea && state.showGameplayHelp));
        helpToggleBtn.textContent = showPlayingArea && state.showGameplayHelp ? "Hide help" : "Help ?";
    }
    if (roomWaitingLine) {
        roomWaitingLine.classList.toggle("hidden", !hasSession || !game || game.status !== "LOBBY");
    }
    if (gameOpenTablesWrap) {
        gameOpenTablesWrap.classList.toggle("hidden", !hasSession || !game || game.status !== "LOBBY");
    }

    if (!hasSession) {
        syncLobbyListPolling();
        return;
    }

    const me = game.players.find(p => p.id === state.playerId);
    const others = game.players.filter(p => p.id !== state.playerId);
    const attacker = game.players.find(p => p.id === game.attackerPlayerId);
    const defender = game.players.find(p => p.id === game.defenderPlayerId);

    gameCodeLabel.textContent = game.code;
    statusLabel.textContent = displayStatus(game.status);
    if (game.status === "IN_PROGRESS" && game.trumpCard) {
        const talonCount = Math.max(0, Number(game.talonSize) || 0);
        if (talonCount > 0) {
            deckArea.classList.remove("hidden");
            trumpUnderImg.src = cardImage(game.trumpCard);
            trumpUnderImg.classList.remove("hidden");
            talonStack.innerHTML = "";
            const face = document.createElement("div");
            face.className = "card-back-face";
            face.setAttribute("aria-hidden", "true");
            const extra = Math.min(Math.max(talonCount - 1, 0), 10);
            if (extra > 0) {
                const y = Math.min(2 + extra * 0.35, 6);
                face.style.boxShadow = `0 ${y}px ${1 + extra * 0.15}px rgba(0,0,0,${0.12 + extra * 0.01})`;
            }
            talonStack.appendChild(face);
        } else {
            deckArea.classList.add("hidden");
            trumpUnderImg.classList.add("hidden");
            trumpUnderImg.removeAttribute("src");
            talonStack.innerHTML = "";
        }
    } else {
        deckArea.classList.add("hidden");
        trumpUnderImg.classList.add("hidden");
        trumpUnderImg.removeAttribute("src");
        talonStack.innerHTML = "";
    }
    if (trumpSuitHud && game.trumpSuit) {
        const sym = trumpSuitGlyph(game.trumpSuit);
        trumpSuitHud.innerHTML = `<span class="pill-role" aria-hidden="true">${sym}</span>`;
        trumpSuitHud.title = `Trump ${sym}`;
        trumpSuitHud.classList.remove("hidden");
    } else if (trumpSuitHud) {
        trumpSuitHud.innerHTML = "";
        trumpSuitHud.classList.add("hidden");
    }
    const host = game.players.find(p => p.id === game.hostPlayerId);
    const playersList = game.players
        .map(p => p.id === game.hostPlayerId ? `${p.name} (host)` : p.name)
        .join(", ");
    roleLabel.textContent = `Players: ${playersList || "—"}`;
    const canStart = Boolean(me && game.status === "LOBBY" && game.hostPlayerId === state.playerId);
    if (startBtn) {
        startBtn.classList.toggle("hidden", !canStart);
    }
    if (addBotBtn) {
        const isHostLobby = Boolean(me && game.status === "LOBBY" && game.hostPlayerId === state.playerId);
        const hasBot = game.players.some(p => p.bot);
        const canAddBot = Boolean(
            isHostLobby &&
            !hasBot &&
            game.playerCount < game.maxPlayers
        );
        addBotBtn.classList.toggle("hidden", !isHostLobby || hasBot);
        addBotBtn.disabled = !canAddBot;
    }

    if (game.status === "FINISHED") {
        tableAttackerLabel.textContent = "—";
        tableDefenderLabel.textContent = "—";
    } else if (game.takingCardsInProgress) {
        tableAttackerLabel.textContent = attacker ? attacker.name : "—";
        if (defender) {
            tableDefenderLabel.innerHTML =
                `${escapeHtml(defender.name)}<span class="pill-take" title="Taking cards">⇩</span>`;
        } else {
            tableDefenderLabel.textContent = "—";
        }
    } else {
        tableAttackerLabel.textContent = attacker ? attacker.name : "—";
        tableDefenderLabel.textContent = defender ? defender.name : "—";
    }
    if (openingLeadHud) {
        const emptyTable = !game.table || game.table.length === 0;
        const showOpening =
            game.status === "IN_PROGRESS" && game.players.length === 4 && emptyTable && attacker;
        if (showOpening) {
            openingLeadHud.textContent =
                `${attacker.name} — attacking; teammates add matching ranks after.`;
            openingLeadHud.classList.remove("hidden");
        } else {
            openingLeadHud.textContent = "";
            openingLeadHud.classList.add("hidden");
        }
    }
    if (me) {
        let cls = "seat-title";
        if (game.players.length === 4 && me.team !== null && me.team !== undefined) {
            cls += ` seat-title--team${me.team}`;
        }
        mySeatTitle.className = cls;
        const myTags = game.status === "IN_PROGRESS" || game.status === "FINISHED" ? roleTags(me, game) : "";
        const myAi = me.bot ? `<span class="ai-badge" title="Bot">🤖</span>` : "";
        const myTagHtml = myTags ? ` <span class="seat-role-inline">${escapeHtml(myTags)}</span>` : "";
        mySeatTitle.innerHTML = `${escapeHtml(me.name)} (you)${myAi}${myTagHtml}`;
        if (myRoleLine) myRoleLine.textContent = "";
    } else {
        mySeatTitle.className = "seat-title";
        mySeatTitle.textContent = "You";
        if (myRoleLine) myRoleLine.textContent = "";
    }

    const playerCount = game.players.length;
    tableGrid.className = "table-grid players-" + playerCount;

    seatLeft.classList.remove("hidden");
    seatRight.classList.remove("hidden");

    if (playerCount === 4) {
        /* Same physical order for everyone: you at bottom; (me+2) is teammate opposite; (me+1)/(me+3) are opponents */
        const myIdx = game.players.findIndex(p => p.id === state.playerId);
        const p = game.players;
        const n = 4;
        if (myIdx >= 0) {
            renderSeat(seatTop1, p[(myIdx + 1) % n], game);
            renderSeat(seatTop2, p[(myIdx + 2) % n], game);
            renderSeat(seatTop3, p[(myIdx + 3) % n], game);
        } else {
            renderSeat(seatTop1, others[0], game);
            renderSeat(seatTop2, others[1], game);
            renderSeat(seatTop3, others[2], game);
        }
        renderSeat(seatLeft, null, game);
        renderSeat(seatRight, null, game);
    } else {
        /*
         * 2 or 3 players: keep a stable relative table order per viewer,
         * same principle as 4-player layout (neighbors by seat index).
         */
        const myIdx = game.players.findIndex(p => p.id === state.playerId);
        const p = game.players;
        const n = playerCount;
        if (myIdx >= 0) {
            renderSeat(seatTop1, p[(myIdx + 1) % n], game);
            renderSeat(seatTop2, n >= 3 ? p[(myIdx + 2) % n] : null, game);
        } else {
            renderSeat(seatTop1, others[0], game);
            renderSeat(seatTop2, others[1], game);
        }
        renderSeat(seatTop3, null, game);
        renderSeat(seatLeft, null, game);
        renderSeat(seatRight, null, game);
    }

    renderBattle(game);
    updateBattleTableBanner(game);
    renderMyHand(game, me);
    renderActionState(game);
    syncLobbyListPolling();
}

async function refreshGame(showMessage = false) {
    if (!state.gameCode) return;
    try {
        const query = new URLSearchParams({viewerPlayerId: state.playerId}).toString();
        state.game = await api(`/api/games/${state.gameCode}?${query}`, "GET");
        syncBotThinkingFromGame(state.game);
        render();
        if (showMessage) log("Game refreshed.");
    } catch (err) {
        if (err.message === "Game not found") {
            clearSession();
            return;
        }
        log(`Refresh failed: ${err.message}`);
    }
}

function syncBotThinkingFromGame(game) {
    const active = game?.botThinking || {};
    const next = {};
    const nextEventAt = {};
    const now = Date.now();
    for (const [playerId, message] of Object.entries(active)) {
        next[playerId] = message || "thinking...";
        nextEventAt[playerId] = state.botThinkingEventAt[playerId] || now;
    }
    state.botThinking = next;
    state.botThinkingEventAt = nextEventAt;
}

async function runAction(name, fn) {
    try {
        await fn();
        syncBotThinkingFromGame(state.game);
        render();
        // The action response already carries updated game state.
        // Suppress the immediate websocket-triggered refetch to avoid double roundtrips.
        state.suppressWsRefreshUntilMs = Date.now() + 1200;
        log(`${name} success.`);
    } catch (err) {
        log(`${name} failed: ${err.message}`);
    }
}

function beginPolling() {
    if (state.pollTimer) clearInterval(state.pollTimer);
    state.pollTimer = setInterval(() => {
        if (!state.wsConnected) {
            refreshGame(false);
            return;
        }
        const inProgress = state.game && state.game.status === "IN_PROGRESS";
        if (!inProgress) {
            refreshGame(false);
            return;
        }
        const now = Date.now();
        if (now - state.lastWsHealthRefreshMs >= 20000) {
            state.lastWsHealthRefreshMs = now;
            refreshGame(false);
        }
    }, 3000);
}

function stopPolling() {
    if (state.pollTimer) clearInterval(state.pollTimer);
    state.pollTimer = null;
}

function closeWebSocket() {
    if (state.ws) state.ws.close();
    state.ws = null;
    state.wsConnected = false;
}

function connectWebSocket() {
    closeWebSocket();
    if (!state.gameCode) return;
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const ws = new WebSocket(`${protocol}//${window.location.host}/ws/games/${state.gameCode}`);
    state.ws = ws;
    ws.onopen = () => { state.wsConnected = true; log("Realtime connected."); };
    ws.onmessage = async (event) => {
        let msgVersion = null;
        let type = "";
        try {
            const data = JSON.parse(event.data || "{}");
            type = data.type || "";
            if (type === "BOT_THINKING" && data.playerId) {
                const eventAtMs = Number.isFinite(Number(data.eventAtMs)) ? Number(data.eventAtMs) : Date.now();
                const currentEventAtMs = state.botThinkingEventAt[data.playerId] || 0;
                if (eventAtMs < currentEventAtMs) {
                    return;
                }
                state.botThinkingEventAt[data.playerId] = eventAtMs;
                if (data.thinking) {
                    state.botThinking[data.playerId] = data.message || "thinking...";
                } else {
                    delete state.botThinking[data.playerId];
                    delete state.botThinkingEventAt[data.playerId];
                }
                render();
                return;
            }
            if (typeof data.version === "number" && Number.isFinite(data.version)) {
                msgVersion = data.version;
            }
        } catch (_) {
            msgVersion = null;
        }
        if (Date.now() < state.suppressWsRefreshUntilMs) {
            return;
        }
        const localVersion = Number(state.game?.version || 0);
        if (msgVersion !== null && msgVersion <= localVersion) {
            return;
        }
        await refreshGame(false);
    };
    ws.onclose = () => { state.wsConnected = false; };
    ws.onerror = () => { state.wsConnected = false; log("Realtime lost, fallback to polling."); };
}

function clearSession() {
    state.gameCode = "";
    state.playerId = "";
    state.game = null;
    state.selectedHandCard = null;
    state.leaveNotified = false;
    saveSession();
    stopPolling();
    closeWebSocket();
    render();
    log("Session cleared.");
}

document.getElementById("createBtn").addEventListener("click", async () => {
    await runAction("Create game", async () => {
        const created = await api("/api/games", "POST", {hostName: hostNameInput.value.trim()});
        state.gameCode = created.game.code;
        state.playerId = created.hostPlayerId;
        state.game = created.game;
        state.selectedHandCard = null;
        saveSession();
        beginPolling();
        connectWebSocket();
    });
});

document.getElementById("joinBtn").addEventListener("click", async () => {
    await runAction("Join game", () => performJoin(null));
});

document.getElementById("leaveBtn").addEventListener("click", async () => {
    const code = state.gameCode;
    const playerId = state.playerId;
    state.leaveNotified = true;
    try {
        if (code && playerId) {
            await api(`/api/games/${code}/leave`, "POST", {playerId});
        }
    } catch (err) {
        log(`Leave notify failed: ${err.message}`);
    } finally {
        clearSession();
    }
});
if (helpToggleBtn) {
    helpToggleBtn.addEventListener("click", () => {
        state.showGameplayHelp = !state.showGameplayHelp;
        render();
    });
}

startBtn.addEventListener("click", async () => runAction("Start", async () => {
    state.game = await api(`/api/games/${state.gameCode}/start`, "POST", {playerId: state.playerId});
}));

attackBtn.addEventListener("click", async () => runAction("Attack", async () => {
    if (!state.selectedHandCard) throw new Error("Select a card first.");
    state.game = await api(`/api/games/${state.gameCode}/attack`, "POST", {playerId: state.playerId, card: state.selectedHandCard});
    state.selectedHandCard = null;
}));

transferBtn.addEventListener("click", async () => runAction("Transfer", async () => {
    if (!state.selectedHandCard) throw new Error("Select a card first.");
    state.game = await api(`/api/games/${state.gameCode}/transfer`, "POST", {playerId: state.playerId, card: state.selectedHandCard});
    state.selectedHandCard = null;
}));

defendBtn.addEventListener("click", async () => runAction("Defend", async () => {
    if (!state.selectedHandCard) throw new Error("Select a card first.");
    const card = state.selectedHandCard;
    const lm = state.game.legalMoves || {};
    const defs = lm.defensesByAttackCard || {};
    const attacksYouCanBeat = Object.keys(defs).filter(atk => (defs[atk] || []).includes(card));
    let target = defendTargetSelect.value;
    if (!attacksYouCanBeat.includes(target)) {
        target = attacksYouCanBeat[0];
    }
    if (!target) throw new Error("No attack card available to defend.");
    state.game = await api(`/api/games/${state.gameCode}/defend`, "POST", {
        playerId: state.playerId,
        attackCard: target,
        defenseCard: card
    });
    state.selectedHandCard = null;
}));

takeBtn.addEventListener("click", async () => runAction("Take cards", async () => {
    state.game = await api(`/api/games/${state.gameCode}/take`, "POST", {playerId: state.playerId});
    state.selectedHandCard = null;
}));

endRoundBtn.addEventListener("click", async () => runAction("End round", async () => {
    state.game = await api(`/api/games/${state.gameCode}/end-round`, "POST", {playerId: state.playerId});
    state.selectedHandCard = null;
}));

if (addBotBtn) {
    addBotBtn.addEventListener("click", async () => runAction("Add bot", async () => {
        state.game = await api(`/api/games/${state.gameCode}/bots`, "POST", {
            playerId: state.playerId
        });
    }));
}

defendTargetSelect.addEventListener("change", () => {
    if (state.game) renderActionState(state.game);
});

let battlePairDropHover = null;
function setBattlePairDropHover(pair) {
    if (battlePairDropHover === pair) return;
    if (battlePairDropHover) battlePairDropHover.classList.remove("battle-pair-drop-target");
    battlePairDropHover = pair;
    if (pair) pair.classList.add("battle-pair-drop-target");
}
function clearBattleDropUi() {
    battleCards.classList.remove("battle-drop-target");
    setBattlePairDropHover(null);
}

battleCards.addEventListener("dragover", (e) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
    const pair = e.target.closest(".battle-pair");
    setBattlePairDropHover(pair);
    /* Row highlight when not over a specific pair (attack / transfer onto open felt). */
    battleCards.classList.toggle("battle-drop-target", !pair);
});
battleCards.addEventListener("dragleave", (e) => {
    if (e.relatedTarget && battleCards.contains(e.relatedTarget)) return;
    clearBattleDropUi();
});
battleCards.addEventListener("drop", async (e) => {
    e.preventDefault();
    const pair = e.target.closest(".battle-pair");
    const preferred = pair?.dataset?.attackCard?.trim() || "";
    clearBattleDropUi();
    const code = (e.dataTransfer.getData("text/plain") || "").trim();
    if (code) await playCardToTable(code, preferred || undefined);
});

window.addEventListener("pagehide", notifyLeaveOnUnload);
window.addEventListener("beforeunload", notifyLeaveOnUnload);

(async function init() {
    if (state.gameCode && state.playerId) {
        beginPolling();
        connectWebSocket();
        await refreshGame();
        log("Session restored.");
    } else {
        render();
    }
})();
