/* Pure presentation helpers live in logic.js (loaded first) so they can be unit-tested. */
const {
    prettyCard,
    sortCardCodesByRank,
    trumpSuitGlyph,
    displayStatus,
    roomCodeFromSearch,
    buildInviteUrl,
    searchWithoutRoomParam,
    reconnectDelayMs,
    gameRefreshDelayMs,
    shouldApplySnapshot,
    parseJsonBody,
    apiErrorMessage,
    sessionErrorKind,
    seatProblem,
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
} = window.DurakLogic;

/*
 * Per-tab session (sessionStorage) so a new tab can stay on the main lobby and see Open tables
 * while another tab hosts a game. A seat is only usable with its secret token: a saved code and
 * player id without one is no session at all.
 */
function loadSavedSession() {
    const saved = {
        gameCode: sessionStorage.getItem("durak_game_code") || "",
        playerId: sessionStorage.getItem("durak_player_id") || "",
        playerToken: sessionStorage.getItem("durak_player_token") || ""
    };
    if (saved.gameCode && saved.playerId && saved.playerToken) return saved;
    if (saved.gameCode || saved.playerId || saved.playerToken) {
        console.warn("Durak: ignoring an incomplete saved seat (no player token).");
        for (const key of ["durak_game_code", "durak_player_id", "durak_player_token"]) {
            sessionStorage.removeItem(key);
        }
    }
    return {gameCode: "", playerId: "", playerToken: ""};
}

const savedSession = loadSavedSession();

const state = {
    gameCode: savedSession.gameCode,
    playerId: savedSession.playerId,
    playerToken: savedSession.playerToken,
    /* Set when the server no longer accepts this browser's seat (wrong/missing token, not seated). */
    seatInvalid: false,
    /* {name} of the one user action whose request is pending; repeat activations are ignored. */
    actionInFlight: null,
    /* True while a /leave request is pending. */
    leaveInFlight: false,
    /* True while a saved seat is being restored after a reload (its first refresh is pending). */
    reconnecting: false,
    /* Current screen (see viewKey); null until the first render. */
    view: null,
    /* Card used by the last play action, so focus can land on its neighbour afterwards. */
    lastPlayedCard: null,
    game: null,
    selectedHandCard: null,
    showGameplayHelp: false,
    pollTimer: null,
    pollDueAt: 0,
    heartbeatTimer: null,
    ws: null,
    wsConnected: false,
    wsReconnectTimer: null,
    wsReconnectAttempt: 0,
    wsStableTimer: null,
    suppressWsRefreshUntilMs: 0,
    gameRefreshInFlight: null,
    gameRefreshQueued: false,
    lobbyWs: null,
    lobbyWsConnected: false,
    lobbyWsReconnectTimer: null,
    lobbyWsReconnectAttempt: 0,
    lobbyWsStableTimer: null,
    lobbyListTimer: null,
    lobbyListDueAt: 0,
    lobbyEventTimer: null,
    lobbyUpdatesActive: false,
    lobbyFetchInFlight: null,
    lobbyRefreshQueued: false,
    lobbyFetchFailures: 0,
    lobbyStreamId: "",
    lobbyRevision: -1,
    botThinking: {},
    botThinkingEventAt: {}
};

const reconnectView = document.getElementById("reconnectView");
const reconnectCode = document.getElementById("reconnectCode");
const lobbyView = document.getElementById("lobbyView");
const gameView = document.getElementById("gameView");
const appAlert = document.getElementById("appAlert");
const liveAnnouncer = document.getElementById("liveAnnouncer");
const playingArea = document.getElementById("playingArea");
const gameplayHintEl = document.getElementById("gameplayHint");
const helpToggleBtn = document.getElementById("helpToggleBtn");
const roomWaitingLine = document.getElementById("roomWaitingLine");
const resultPanel = document.getElementById("resultPanel");
const resultIcon = document.getElementById("resultIcon");
const resultTitle = document.getElementById("resultTitle");
const resultSummary = document.getElementById("resultSummary");
const rematchWaiting = document.getElementById("rematchWaiting");
const seatNotice = document.getElementById("seatNotice");
const seatNoticeText = document.getElementById("seatNoticeText");
const seatNoticeLobbyBtn = document.getElementById("seatNoticeLobbyBtn");
const messagesPanel = document.getElementById("messagesPanel");
const messages = document.getElementById("messages");
const debugUi = new URLSearchParams(window.location.search).get("debug") === "1";
if (debugUi && messagesPanel) messagesPanel.classList.remove("hidden");
const hostNameInput = document.getElementById("hostName");
const publicRoomInput = document.getElementById("publicRoom");
const gameCodeInput = document.getElementById("gameCode");
const playerNameInput = document.getElementById("playerName");
const joinHint = document.getElementById("joinHint");
const gameCodeLabel = document.getElementById("gameCodeLabel");
const statusLabel = document.getElementById("statusLabel");
const visibilityLabel = document.getElementById("visibilityLabel");
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
const quickPlayBtn = document.getElementById("quickPlayBtn");
const shareBtn = document.getElementById("shareBtn");
const rematchBtn = document.getElementById("rematchBtn");
const createBtn = document.getElementById("createBtn");
const joinBtn = document.getElementById("joinBtn");
const leaveBtn = document.getElementById("leaveBtn");
const leaveDialog = document.getElementById("leaveDialog");

function log(message) {
    if (!debugUi || !messages) return;
    const now = new Date().toLocaleTimeString();
    messages.textContent = `[${now}] ${message}`;
}

function clearError(kind = null) {
    if (!appAlert) return;
    if (kind && appAlert.dataset.kind !== kind) return;
    appAlert.textContent = "";
    delete appAlert.dataset.kind;
    appAlert.classList.add("hidden");
}

/**
 * Polite screen reader announcement. Each message is appended as a new node, which live
 * regions announce reliably even when the same words repeat; old lines are pruned.
 */
function announce(message) {
    if (!liveAnnouncer || !message) return;
    const line = document.createElement("p");
    line.textContent = message;
    liveAnnouncer.appendChild(line);
    while (liveAnnouncer.childElementCount > 5) {
        liveAnnouncer.firstElementChild.remove();
    }
}

function showError(message, kind = "action") {
    if (!appAlert) return;
    appAlert.textContent = message || "Something went wrong. Please try again.";
    appAlert.dataset.kind = kind;
    appAlert.classList.remove("hidden");
}

/* Capability token proving we own state.playerId. There is no fallback: no token, no seat. */
function authHeaders() {
    return state.playerToken ? {"X-Durak-Token": state.playerToken} : {};
}

/** Every game request path goes through here so a stored or typed code is always encoded. */
function gamePath(code, suffix = "") {
    return `/api/games/${encodeURIComponent(code)}${suffix}`;
}

const API_TIMEOUT_MS = 20_000;

function isBusy() {
    return Boolean(state.actionInFlight || state.leaveInFlight);
}

/**
 * Enables or disables an action control. While a request is pending, the control that has
 * keyboard focus is only marked aria-disabled: a disabled button drops focus to <body>.
 */
function setControlEnabled(btn, enabled) {
    if (!btn) return;
    if (enabled) {
        btn.disabled = false;
        btn.removeAttribute("aria-disabled");
    } else if (isBusy() && document.activeElement === btn) {
        btn.disabled = false;
        btn.setAttribute("aria-disabled", "true");
    } else {
        btn.disabled = true;
        btn.removeAttribute("aria-disabled");
    }
}

function lobbyActionButtons() {
    const listButtons = lobbyGameList ? [...lobbyGameList.querySelectorAll(".lobby-list-join")] : [];
    return [quickPlayBtn, createBtn, joinBtn, ...listButtons];
}

/** A failed request; `status` is the HTTP status, or 0 when the server could not be reached. */
class ApiError extends Error {
    constructor(status, message) {
        super(message);
        this.name = "ApiError";
        this.status = status;
    }
}

/**
 * JSON request helper. Resolves to the decoded body (null for an empty one such as /leave) and
 * rejects with an ApiError carrying {status, message}, including for network failures and for
 * non-JSON error pages from a proxy.
 */
async function api(path, method, body) {
    /* A request that never settles would otherwise keep the one-action-at-a-time guard busy. */
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), API_TIMEOUT_MS);
    let res;
    let text = "";
    try {
        try {
            res = await fetch(path, {
                method,
                headers: {"Content-Type": "application/json", ...authHeaders()},
                body: body ? JSON.stringify(body) : undefined,
                signal: controller.signal
            });
        } catch (_) {
            throw new ApiError(0, controller.signal.aborted
                ? "The server took too long to respond. Please try again."
                : apiErrorMessage(0, null));
        }
        try {
            text = await res.text();
        } catch (_) {
            text = "";
        }
    } finally {
        window.clearTimeout(timeout);
    }
    const parsed = parseJsonBody(res.headers.get("content-type"), text);
    if (!res.ok) {
        throw new ApiError(res.status, apiErrorMessage(res.status, parsed.value));
    }
    if (!parsed.ok) {
        throw new ApiError(res.status, "Unexpected response from the server. Please try again.");
    }
    return parsed.value;
}

/** Identifies the seat a request was made for, so late responses cannot leak into another session. */
function sessionKey() {
    return `${state.gameCode}\u0000${state.playerId}`;
}

function isCurrentSession(key) {
    return Boolean(state.gameCode) && key === sessionKey();
}

/**
 * The single entry point for game state from the server (refreshes, action responses, joins).
 * Rejects snapshots for another seat or older than the one on screen, then renders.
 */
function applyGameSnapshot(snapshot, key = sessionKey()) {
    if (!isCurrentSession(key) || !snapshot || snapshot.code !== state.gameCode) return false;
    if (!shouldApplySnapshot(state.game, snapshot)) {
        log(`Ignored stale game version ${snapshot.version}; current version is ${state.game?.version}.`);
        return false;
    }
    const previous = state.game;
    if (previous && previous.code === snapshot.code && previous.status !== snapshot.status) {
        state.selectedHandCard = null;
    }
    const announcements = describeTransition(previous, snapshot, state.playerId);
    state.game = snapshot;
    if (announcements.length) announce(announcements.join(" "));
    const me = snapshot.players.find(p => p.id === state.playerId);
    if (state.selectedHandCard && !(me?.hand || []).includes(state.selectedHandCard)) {
        state.selectedHandCard = null;
    }
    syncBotThinkingFromGame(snapshot);
    const problem = seatProblem(snapshot, state.playerId);
    if (problem) {
        markSeatInvalid(problem);
    } else {
        render();
    }
    return true;
}

/**
 * Handles failures that invalidate the saved seat. Returns true when the error was consumed:
 * 404/410 (room gone) end the session, 403 means this browser's seat can no longer act.
 */
function handleSessionError(err, key = sessionKey()) {
    if (!err || !isCurrentSession(key)) return false;
    const kind = sessionErrorKind(err.status);
    const code = state.gameCode;
    if (kind === "room-gone") {
        clearSession();
        showError(err.status === 410
            ? `Room ${code} expired due to inactivity.`
            : `Room ${code} no longer exists.`, "session");
        return true;
    }
    if (kind === "seat-invalid") {
        markSeatInvalid(`rejected with ${err.status}`);
        return true;
    }
    return false;
}

/**
 * The server no longer accepts this browser's seat (a wrong or missing token, or the player is
 * not seated): it would keep answering with the public view, which looks like a hand of zero
 * cards with every button disabled. Stop updates and explain the way out instead.
 */
function markSeatInvalid(reason) {
    if (!state.gameCode) return;
    if (!state.seatInvalid) {
        console.warn(`Durak: this browser's seat in room ${state.gameCode} is no longer valid (${reason}).`);
    }
    state.seatInvalid = true;
    state.selectedHandCard = null;
    stopPolling();
    closeWebSocket();
    render();
}

/** "Back to lobby" from an invalid seat: forget it locally and offer to join the room again. */
function abandonInvalidSeat() {
    const code = state.gameCode;
    clearSession();
    clearError();
    if (code) {
        gameCodeInput.value = code;
        if (joinHint) {
            joinHint.textContent = `Room ${code} is filled in. Join again for a new seat, or create a room.`;
        }
    }
}

function saveSession() {
    sessionStorage.setItem("durak_game_code", state.gameCode || "");
    sessionStorage.setItem("durak_player_id", state.playerId || "");
    sessionStorage.setItem("durak_player_token", state.playerToken || "");
}

/** An invite is one-shot navigation state; once a game is adopted it must not replay on reload. */
function clearConsumedInvite() {
    const search = searchWithoutRoomParam(window.location.search);
    if (search === window.location.search) return;
    try {
        window.history.replaceState(
            window.history.state,
            "",
            `${window.location.pathname}${search}${window.location.hash}`
        );
    } catch (error) {
        log(`Could not clear invite URL: ${error?.message || error}`);
    }
}

async function refreshLobbyLists() {
    if (state.lobbyFetchInFlight) {
        state.lobbyRefreshQueued = true;
        return state.lobbyFetchInFlight;
    }

    const request = (async () => {
        try {
            const res = await fetch("/api/lobbies", {cache: "no-store"});
            if (!res.ok) throw new Error("bad");
            const rows = await res.json();

            const emptyHome = "<p class=\"lobby-list-empty muted\">No open tables yet. Create one above or enter a code.</p>";
            const emptyInRoom = "<p class=\"lobby-list-empty muted\">No lobby tables returned. Try Refresh or wait a moment.</p>";

            /* Always fill #lobbyGameList when data arrives; do not gate on lobbyView visibility (async fetch can race with show/hide). */
            if (lobbyGameList) {
                const focusedCode = lobbyGameList.contains(document.activeElement)
                    ? document.activeElement.getAttribute("data-code")
                    : null;
                lobbyGameList.innerHTML = rows.length ? lobbyRowsHtml(rows, true, null) : emptyHome;
                if (focusedCode !== null) {
                    // The list was rebuilt under a keyboard user: keep them on the same table's
                    // Join button, or the first one, or the list heading if the list emptied.
                    const buttons = [...lobbyGameList.querySelectorAll(".lobby-list-join")];
                    const again = buttons.find(btn => btn.getAttribute("data-code") === focusedCode)
                        || buttons[0]
                        || document.getElementById("openTablesHeading");
                    again?.focus();
                }
                // After restoring focus, so a pending join keeps its (focused) button aria-disabled.
                syncBusyControls();
            }
            if (gameLobbyGameList) {
                const code = state.gameCode || "";
                const inRoom = gameOpenTablesWrap && !gameOpenTablesWrap.classList.contains("hidden");
                gameLobbyGameList.innerHTML = rows.length
                    ? lobbyRowsHtml(rows, false, code)
                    : (inRoom ? emptyInRoom : "");
            }
            return true;
        } catch (error) {
            const err = "<p class=\"lobby-list-empty muted\">Could not load open tables. Try refreshing the page.</p>";
            try {
                if (lobbyGameList) lobbyGameList.innerHTML = err;
                if (gameLobbyGameList) gameLobbyGameList.innerHTML = err;
            } catch (_) {
                // A broken renderer must not keep the single-flight lock held forever.
            }
            log(`Open tables refresh failed: ${error?.message || "unknown error"}`);
            return false;
        }
    })();

    state.lobbyFetchInFlight = request;
    let succeeded = false;
    try {
        succeeded = await request;
        return succeeded;
    } finally {
        if (state.lobbyFetchInFlight === request) {
            state.lobbyFetchInFlight = null;
        }
        state.lobbyFetchFailures = succeeded ? 0 : Math.min(state.lobbyFetchFailures + 1, 3);
        scheduleLobbyListPoll(null, true);

        if (state.lobbyRefreshQueued) {
            state.lobbyRefreshQueued = false;
            queueLobbyRefresh(0);
        }
    }
}

function shouldPollOpenTables() {
    if (state.reconnecting) {
        return false;
    }
    if (!state.gameCode || !state.playerId || !state.game) {
        return true;
    }
    return state.game.status === "LOBBY" && state.game.publicRoom !== false;
}

function syncLobbyListPolling() {
    const shouldRun = shouldPollOpenTables() && document.visibilityState !== "hidden";
    if (shouldRun) {
        const becameActive = !state.lobbyUpdatesActive;
        state.lobbyUpdatesActive = true;
        connectLobbyWebSocket();
        if (becameActive) {
            refreshLobbyLists();
        } else if (!state.lobbyListTimer && !state.lobbyFetchInFlight) {
            scheduleLobbyListPoll();
        }
    } else {
        stopLobbyListPolling();
    }
}

function scheduleLobbyListPoll(delayOverride = null, replaceExisting = false) {
    if (!state.lobbyUpdatesActive || !shouldPollOpenTables()) return;
    const delay = delayOverride ?? lobbyRefreshDelayMs(
        state.lobbyWsConnected,
        state.lobbyFetchFailures,
        document.visibilityState
    );
    if (delay == null) return;
    const dueAt = Date.now() + delay;
    if (state.lobbyListTimer && !replaceExisting && state.lobbyListDueAt <= dueAt) {
        return;
    }
    if (state.lobbyListTimer) {
        clearTimeout(state.lobbyListTimer);
    }
    state.lobbyListDueAt = dueAt;
    state.lobbyListTimer = window.setTimeout(() => {
        state.lobbyListTimer = null;
        state.lobbyListDueAt = 0;
        refreshLobbyLists();
    }, delay);
}

function queueLobbyRefresh(delay = 75) {
    if (!state.lobbyUpdatesActive || document.visibilityState === "hidden") return;
    if (state.lobbyEventTimer) clearTimeout(state.lobbyEventTimer);
    state.lobbyEventTimer = window.setTimeout(() => {
        state.lobbyEventTimer = null;
        refreshLobbyLists();
    }, delay);
}

function stopLobbyListPolling() {
    state.lobbyUpdatesActive = false;
    state.lobbyRefreshQueued = false;
    if (state.lobbyListTimer) {
        clearTimeout(state.lobbyListTimer);
        state.lobbyListTimer = null;
        state.lobbyListDueAt = 0;
    }
    if (state.lobbyEventTimer) {
        clearTimeout(state.lobbyEventTimer);
        state.lobbyEventTimer = null;
    }
    closeLobbyWebSocket();
}

function closeLobbyWebSocket() {
    if (state.lobbyWsReconnectTimer) {
        clearTimeout(state.lobbyWsReconnectTimer);
        state.lobbyWsReconnectTimer = null;
    }
    if (state.lobbyWsStableTimer) {
        clearTimeout(state.lobbyWsStableTimer);
        state.lobbyWsStableTimer = null;
    }
    const ws = state.lobbyWs;
    state.lobbyWs = null;
    state.lobbyWsConnected = false;
    state.lobbyWsReconnectAttempt = 0;
    if (ws) {
        ws.onopen = null;
        ws.onmessage = null;
        ws.onclose = null;
        ws.onerror = null;
        if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
            ws.close();
        }
    }
}

function scheduleLobbyWebSocketReconnect() {
    if (state.lobbyWsReconnectTimer || !state.lobbyUpdatesActive || document.visibilityState === "hidden") {
        return;
    }
    const delay = reconnectDelayMs(state.lobbyWsReconnectAttempt++);
    state.lobbyWsReconnectTimer = window.setTimeout(() => {
        state.lobbyWsReconnectTimer = null;
        connectLobbyWebSocket();
    }, delay);
}

function connectLobbyWebSocket() {
    if (!state.lobbyUpdatesActive || document.visibilityState === "hidden" || state.lobbyWsReconnectTimer) {
        return;
    }
    const current = state.lobbyWs;
    if (current && (current.readyState === WebSocket.OPEN || current.readyState === WebSocket.CONNECTING)) {
        return;
    }

    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const ws = new WebSocket(`${protocol}//${window.location.host}/ws/lobbies`);
    state.lobbyWs = ws;
    ws.onopen = () => {
        if (state.lobbyWs !== ws) return;
        if (state.lobbyWsStableTimer) clearTimeout(state.lobbyWsStableTimer);
        state.lobbyWsStableTimer = window.setTimeout(() => {
            state.lobbyWsStableTimer = null;
            if (state.lobbyWs === ws && ws.readyState === WebSocket.OPEN) {
                state.lobbyWsReconnectAttempt = 0;
            }
        }, 5_000);
        log("Open tables realtime transport connected.");
    };
    ws.onmessage = (event) => {
        if (state.lobbyWs !== ws) return;
        try {
            const data = JSON.parse(event.data || "{}");
            if (data.type !== "LOBBIES_READY" && data.type !== "LOBBIES_CHANGED") return;
            const streamId = String(data.streamId || "");
            if (streamId && streamId !== state.lobbyStreamId) {
                state.lobbyStreamId = streamId;
                state.lobbyRevision = -1;
            }
            const revision = Number(data.revision);
            let hasNewRevision = true;
            if (Number.isFinite(revision)) {
                hasNewRevision = revision > state.lobbyRevision;
                if (hasNewRevision) state.lobbyRevision = revision;
            }
            state.lobbyWsConnected = true;
            scheduleLobbyListPoll(null, true);
            log("Open tables realtime connected.");
            if (!hasNewRevision) return;
            queueLobbyRefresh(data.type === "LOBBIES_READY" ? 0 : 75);
        } catch (_) {
            // Ignore malformed invalidations; the health refresh remains authoritative.
        }
    };
    ws.onclose = () => {
        if (state.lobbyWs !== ws) return;
        if (state.lobbyWsStableTimer) {
            clearTimeout(state.lobbyWsStableTimer);
            state.lobbyWsStableTimer = null;
        }
        state.lobbyWs = null;
        state.lobbyWsConnected = false;
        scheduleLobbyListPoll();
        scheduleLobbyWebSocketReconnect();
    };
    ws.onerror = () => {
        if (state.lobbyWs !== ws) return;
        state.lobbyWsConnected = false;
        scheduleLobbyListPoll();
        log("Open tables realtime lost; using fallback refreshes.");
        try { ws.close(); } catch (_) { /* close callback will schedule reconnect when possible */ }
    };
}

document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") {
        stopLobbyListPolling();
        pauseGameUpdates();
        return;
    }
    syncLobbyListPolling();
    if (state.gameCode && !state.seatInvalid) {
        beginPolling();
        sendHeartbeat();
        connectWebSocket();
        refreshGame(false);
    }
});

async function performJoin(roomCode) {
    const raw = roomCode != null && String(roomCode).trim() !== ""
        ? String(roomCode).trim()
        : gameCodeInput.value.trim();
    if (!raw) throw new Error("Enter a room code.");
    const code = normalizeRoomCode(raw);
    if (!code) {
        throw new Error("That room code is not valid. Room codes have 6 letters and digits (no 0, 1, I or O).");
    }
    const joined = await api(gamePath(code, "/join"), "POST", {playerName: playerNameInput.value.trim()});
    adoptSession(joined?.game, joined?.playerId, joined?.playerToken);
}

function adoptCreatedGame(created) {
    adoptSession(created?.game, created?.hostPlayerId, created?.playerToken);
}

/** Takes over the seat returned by create/join/quick play and shows its game. */
function adoptSession(game, playerId, playerToken) {
    if (!game || typeof game.code !== "string" || !Array.isArray(game.players) || !playerId || !playerToken) {
        throw new ApiError(200, "Unexpected response from the server. Please try again.");
    }
    closeWebSocket();
    state.gameCode = game.code;
    state.playerId = String(playerId);
    state.playerToken = String(playerToken);
    state.seatInvalid = false;
    state.game = null;
    state.selectedHandCard = null;
    saveSession();
    clearConsumedInvite();
    applyGameSnapshot(game);
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

/** Only real card codes reach a URL; anything else shows a card back. */
function cardImage(code) {
    return isCardCode(code) ? `/cards/${code}.png` : "/cards/BACK.png";
}

/** Card image; `alt` defaults to the spoken card name ("" when a parent already names it). */
function cardImg(code, className, alt = cardName(code)) {
    const img = document.createElement("img");
    if (className) img.className = className;
    img.src = cardImage(code);
    img.alt = alt;
    return img;
}

function renderSeat(el, player, game) {
    if (!player) {
        el.innerHTML = "";
        delete el.dataset.playerId;
        return;
    }
    el.dataset.playerId = player.id;
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
    const teamClass = game.players.length === 4 && Number.isInteger(player.team)
        ? ` seat-title--team${player.team}` : "";
    /* <wbr>: narrow seats may wrap between the name and its badges instead of mid-word. */
    /* The fan exposes only what it shows: an exact count below six, "6 or more" otherwise. */
    el.innerHTML = `<div class="seat-title${teamClass}">${escapeHtml(player.name)}${botBadgeHtml(player)}<wbr>${roleTagsHtml(player, game)}<wbr></div>
        <div class="${fanClass}" role="img" aria-label="${escapeHtml(fanCountLabel(player.handSize))}">${backs}</div>`;
    syncSeatThinking(el);
}

function botBadgeHtml(player) {
    return player.bot
        ? `<span class="ai-badge" title="Bot" aria-hidden="true">🤖</span><span class="visually-hidden"> (bot)</span>`
        : "";
}

/** Emoji role tags for the eye, the same roles in words for screen readers. */
function roleTagsHtml(player, game) {
    const tags = roleTags(player, game);
    if (!tags) return "";
    return `<span class="seat-role-inline" aria-hidden="true">${escapeHtml(tags)}</span>`
        + `<span class="visually-hidden">, ${escapeHtml(roleDescription(player, game))}</span>`;
}

/** Shows or clears the "planning attack..." note of the bot in this seat, in place. */
function syncSeatThinking(el) {
    const title = el.querySelector(".seat-title");
    if (!title) return;
    const message = el.dataset.playerId ? state.botThinking[el.dataset.playerId] : "";
    let note = title.querySelector(".bot-thinking-inline");
    if (!message) {
        note?.remove();
        return;
    }
    if (!note) {
        note = document.createElement("span");
        note.className = "bot-thinking-inline";
        // The last word and the dots never wrap apart in narrow seats.
        const tail = document.createElement("span");
        tail.className = "bot-thinking-tail";
        tail.append(document.createTextNode(""), Object.assign(document.createElement("span"), {className: "bot-thinking-dots"}));
        note.append(document.createTextNode(""), tail);
        title.appendChild(note);
    }
    // The animated dots follow the text, so drop the server's own trailing ellipsis.
    const text = String(message).replace(/(\.{3}|…)$/, "");
    const cut = text.lastIndexOf(" ") + 1;
    note.firstChild.textContent = text.slice(0, cut);
    note.lastChild.firstChild.textContent = text.slice(cut);
}

/**
 * BOT_THINKING only changes these notes. Updating them in place (instead of a full render)
 * keeps keyboard focus and selection untouched while a bot deliberates.
 */
function updateBotThinkingIndicators() {
    for (const seat of [seatTop1, seatTop2, seatTop3]) {
        if (seat) syncSeatThinking(seat);
    }
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
        : "No more throw-ins. All attackers must press End round.";
    battleTableBanner.innerHTML =
        `<span class="taking-lead-icon" aria-hidden="true">⇩</span><strong>${escapeHtml(takingName)}</strong> is taking cards. ${sub}`;
    battleTableBanner.classList.remove("hidden");
}

function renderBattle(game) {
    battleCards.innerHTML = "";
    if (!game.table || game.table.length === 0) {
        battleCards.removeAttribute("role");
        battleCards.removeAttribute("aria-label");
        battleCards.innerHTML = "<div class='muted'>No cards on table</div>";
        return;
    }
    battleCards.setAttribute("role", "list");
    battleCards.setAttribute("aria-label", "Cards on the table");
    for (const pairData of game.table) {
        const pair = document.createElement("div");
        pair.className = "battle-pair";
        pair.setAttribute("role", "listitem");
        pair.dataset.attackCard = String(pairData.attackCard || "");
        // One spoken description per pair ("7 of hearts, beaten by 9 of hearts"); images are decorative.
        const label = document.createElement("span");
        label.className = "visually-hidden";
        label.textContent = tablePairLabel(pairData);
        pair.appendChild(label);
        pair.appendChild(cardImg(pairData.attackCard, "battle-card attack", ""));
        if (pairData.defenseCard) {
            pair.appendChild(cardImg(pairData.defenseCard, "battle-card defense", ""));
        }
        battleCards.appendChild(pair);
    }
}

function currentHandCodes() {
    return [...myHand.querySelectorAll(".hand-card-btn")].map(btn => btn.dataset.cardCode);
}

function createHandCardButton(code) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "hand-card-btn";
    btn.dataset.cardCode = code;
    const img = cardImg(code, null, "");
    img.draggable = false;
    btn.appendChild(img);
    // Clicking selects (aria-pressed); it does not play, so the name is just the card.
    btn.setAttribute("aria-label", cardName(code));
    btn.addEventListener("click", () => toggleHandCard(code));
    btn.addEventListener("dragstart", (e) => {
        if (isBusy()) {
            e.preventDefault();
            return;
        }
        btn.classList.add("dragging");
        e.dataTransfer.setData("text/plain", code);
        e.dataTransfer.effectAllowed = "move";
    });
    btn.addEventListener("dragend", () => {
        btn.classList.remove("dragging");
        clearBattleDropUi();
    });
    return btn;
}

function toggleHandCard(code) {
    if (isBusy() || !state.game) return;
    state.selectedHandCard = state.selectedHandCard === code ? null : code;
    renderActionState(state.game);
    renderMyHand(state.game, state.game.players.find(p => p.id === state.playerId));
}

/**
 * Keyed update of the hand: buttons are reused per card code and existing ones are never moved
 * (the sort order is stable), so the focused card keeps focus through every re-render.
 */
function renderMyHand(game, me) {
    const hand = sortCardCodesByRank(me?.hand || []);
    const busy = isBusy();
    const existing = new Map([...myHand.querySelectorAll(".hand-card-btn")].map(btn => [btn.dataset.cardCode, btn]));
    const wanted = new Set(hand);
    for (const [code, btn] of existing) {
        if (!wanted.has(code)) btn.remove();
    }
    for (const node of [...myHand.childNodes]) {
        if (!(node instanceof HTMLElement && node.classList.contains("hand-card-btn"))) node.remove();
    }
    let previous = null;
    for (const code of hand) {
        const btn = existing.get(code) || createHandCardButton(code);
        const selected = state.selectedHandCard === code;
        btn.classList.toggle("selected", selected);
        btn.setAttribute("aria-pressed", String(selected));
        /* While a request is pending the hand stays focusable but cannot be changed or dragged. */
        btn.draggable = !busy;
        if (busy) btn.setAttribute("aria-disabled", "true");
        else btn.removeAttribute("aria-disabled");
        const slot = previous ? previous.nextElementSibling : myHand.firstElementChild;
        if (btn !== slot) myHand.insertBefore(btn, slot);
        previous = btn;
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
        opt.textContent = `vs ${cardName(atk)}`;
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

    const idle = !isBusy();
    setControlEnabled(startBtn, idle && Boolean(lm.canStart));
    setControlEnabled(attackBtn, idle && Boolean(lm.canAttack && selected && attackable.includes(selected)));
    setControlEnabled(transferBtn, idle && Boolean(lm.canTransfer && selected && transferable.includes(selected)));
    setControlEnabled(defendBtn, idle && canDefendSelected);
    setControlEnabled(takeBtn, idle && Boolean(lm.canTake));
    setControlEnabled(endRoundBtn, idle && Boolean(lm.canEndRound));

    if (game.takingCardsInProgress) {
        actionHint.textContent = "See the message on the table. Use buttons or drag cards.";
        return;
    }

    if (lm.canEndRound) {
        actionHint.textContent = lm.canAttack
            ? "All attacks are defended. You may add another attack (matching rank) or press End round."
            : "All attacks are defended. Press End round to finish this bout.";
    } else if (!selected) {
        if (team4 && tableEmpty && iOnAttackSide && iAmOpeningAttacker) {
            actionHint.textContent = "Your turn. Lead the first attack (⚔️ opening attacker).";
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
    if (!game || game.status !== "IN_PROGRESS" || !cardCode || isBusy()) return;
    const lm = game.legalMoves || {};
    const attackable = lm.attackableCardCodes || [];
    const transferable = lm.transferableCardCodes || [];
    const defs = lm.defensesByAttackCard || {};
    const attacksYouCanBeat = Object.keys(defs).filter(atk => (defs[atk] || []).includes(cardCode));

    if (lm.canTransfer && transferable.includes(cardCode)) {
        await runAction("Transfer", playerAction("/transfer", {card: cardCode}));
        return;
    }
    if (lm.canAttack && attackable.includes(cardCode)) {
        await runAction("Attack", playerAction("/attack", {card: cardCode}));
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
        await runAction("Defend", playerAction("/defend", {attackCard: target, defenseCard: cardCode}));
        return;
    }
    log(`Cannot play ${cardCode} to the table right now.`);
}

/**
 * Builds a runAction body for a seat action: POSTs {playerId, ...extra} and resolves to the
 * returned game snapshot, which runAction applies through the version check.
 */
function playerAction(suffix, extra = {}, {clearSelection = true} = {}) {
    return async () => {
        state.lastPlayedCard = extra.card || extra.defenseCard || null;
        const game = await api(gamePath(state.gameCode, suffix), "POST", {playerId: state.playerId, ...extra});
        if (clearSelection) state.selectedHandCard = null;
        return game;
    };
}

function syncBusyControls() {
    for (const btn of lobbyActionButtons()) setControlEnabled(btn, !isBusy());
    setControlEnabled(leaveBtn, !state.leaveInFlight);
}

/** Renders the current state, then puts keyboard focus somewhere meaningful (see settleFocus). */
function render() {
    const focusBefore = describeFocus();
    const handBefore = currentHandCodes();
    renderView();
    settleFocus(focusBefore, handBefore);
}

/** What has focus, in terms that survive a re-render: a card code or a control id. */
function describeFocus() {
    const active = document.activeElement;
    if (!active || active === document.body) return null;
    if (active.classList.contains("hand-card-btn") && myHand.contains(active)) {
        return {kind: "card", code: active.dataset.cardCode};
    }
    return active.id ? {kind: "control", id: active.id} : null;
}

function canTakeFocus(el) {
    return Boolean(el && el.isConnected && !el.disabled && el.getClientRects().length > 0);
}

const VIEW_HEADINGS = {lobby: "lobbyHeading", room: "roomHeading", table: "roomHeading", result: "resultTitle"};

/** Safe places to move focus to when the focused control goes away (never Leave or Take). */
const FALLBACK_CONTROL_IDS = ["startBtn", "addBotBtn", "shareBtn", "rematchBtn"];

/**
 * After a render: a new view (lobby -> game, game -> result, ...) focuses its heading; otherwise,
 * if the focused card or control was removed, hidden or disabled, focus moves to its nearest
 * neighbour in the hand or another sensible control instead of falling back to <body>.
 */
function settleFocus(before, handBefore) {
    const view = viewKey({
        reconnecting: state.reconnecting,
        hasSession: Boolean(state.gameCode && state.playerId && state.game),
        status: state.game?.status
    });
    const previousView = state.view;
    state.view = view;
    // Restoring a seat after a reload is still page load: leave focus where the browser put it.
    if (previousView && previousView !== "reconnecting" && previousView !== view) {
        const heading = document.getElementById(VIEW_HEADINGS[view] || "");
        if (canTakeFocus(heading)) {
            heading.focus();
            return;
        }
    }
    if (!before) return;
    const active = document.activeElement;
    if (active && active !== document.body && canTakeFocus(active)) return;

    const handAfter = currentHandCodes();
    const lostEl = before.kind === "control" ? document.getElementById(before.id) : null;
    const target = focusRecoveryTarget({
        lost: before,
        handBefore,
        handAfter,
        lastPlayedCard: state.lastPlayedCard,
        stillAvailable: canTakeFocus(lostEl),
        availableControls: FALLBACK_CONTROL_IDS.filter(id => canTakeFocus(document.getElementById(id)))
    });
    let el = null;
    if (target.kind === "card") {
        el = [...myHand.querySelectorAll(".hand-card-btn")].find(btn => btn.dataset.cardCode === target.code);
    } else if (target.kind === "control") {
        el = document.getElementById(target.id);
    } else {
        el = document.getElementById(VIEW_HEADINGS[view] || "");
    }
    if (canTakeFocus(el)) el.focus();
}

function renderView() {
    const game = state.game;
    const hasSession = Boolean(state.gameCode && state.playerId && game);
    const reconnecting = Boolean(state.reconnecting && !hasSession);
    syncBusyControls();
    if (reconnectView) {
        reconnectView.classList.toggle("hidden", !reconnecting);
        if (reconnectCode) reconnectCode.textContent = reconnecting ? state.gameCode : "";
    }
    lobbyView.classList.toggle("hidden", hasSession || reconnecting);
    gameView.classList.toggle("hidden", !hasSession);

    const showPlayingArea = hasSession && game && game.status === "IN_PROGRESS";
    const showResult = hasSession && game && game.status === "FINISHED";
    gameView.classList.toggle("game-view--active", Boolean(showPlayingArea || showResult));
    if (playingArea) playingArea.classList.toggle("hidden", !showPlayingArea);
    if (resultPanel) resultPanel.classList.toggle("hidden", !showResult);
    if (gameplayHintEl) gameplayHintEl.classList.toggle("hidden", !showPlayingArea || !state.showGameplayHelp);
    if (helpToggleBtn) {
        helpToggleBtn.classList.toggle("hidden", !showPlayingArea);
        helpToggleBtn.setAttribute("aria-expanded", String(showPlayingArea && state.showGameplayHelp));
        helpToggleBtn.textContent = showPlayingArea && state.showGameplayHelp ? "Hide help" : "Help ?";
    }
    if (roomWaitingLine) {
        roomWaitingLine.classList.toggle("hidden", !hasSession || !game || game.status !== "LOBBY");
        if (hasSession && game?.status === "LOBBY") {
            roomWaitingLine.textContent = game.publicRoom !== false
                ? "You’re in the room. Share the invite or let players find it under Open tables."
                : "This room is invite-only. Share the invite link or code with friends.";
        }
    }
    if (gameOpenTablesWrap) {
        gameOpenTablesWrap.classList.toggle(
            "hidden", !hasSession || !game || game.status !== "LOBBY" || game.publicRoom === false);
    }
    if (shareBtn) {
        shareBtn.classList.toggle("hidden", !hasSession || !game || game.status !== "LOBBY");
    }
    if (seatNotice) {
        const showNotice = Boolean(hasSession && state.seatInvalid);
        seatNotice.classList.toggle("hidden", !showNotice);
        const text = showNotice
            ? `This browser's seat in room ${state.gameCode} is no longer valid. Go back to the lobby to join the room again or start a new game.`
            : "";
        if (seatNoticeText.textContent !== text) seatNoticeText.textContent = text;
    }

    if (!hasSession) {
        syncLobbyListPolling();
        return;
    }

    const me = game.players.find(p => p.id === state.playerId);
    const others = game.players.filter(p => p.id !== state.playerId);
    const attacker = game.players.find(p => p.id === game.attackerPlayerId);
    const defender = game.players.find(p => p.id === game.defenderPlayerId);

    if (showResult && resultPanel) {
        const result = gameResult(game, state.playerId);
        resultPanel.dataset.outcome = result?.outcome || "draw";
        resultIcon.textContent = result?.icon || "🃏";
        resultTitle.textContent = result?.title || "Game finished";
        resultSummary.textContent = result?.summary || "The game is complete.";
        const canRematch = game.hostPlayerId === state.playerId;
        rematchBtn.classList.toggle("hidden", !canRematch);
        setControlEnabled(rematchBtn, !isBusy());
        rematchWaiting.classList.toggle("hidden", canRematch);
    }

    gameCodeLabel.textContent = game.code;
    statusLabel.textContent = displayStatus(game.status);
    if (visibilityLabel) {
        const isPublic = game.publicRoom !== false;
        visibilityLabel.textContent = isPublic ? "Public room" : "Invite only";
        visibilityLabel.title = isPublic
            ? "This waiting room appears in Open tables."
            : "Only people with the room code can join.";
    }
    if (game.status === "IN_PROGRESS" && game.trumpCard) {
        const talonCount = Math.max(0, Number(game.talonSize) || 0);
        if (talonCount > 0) {
            deckArea.classList.remove("hidden");
            trumpUnderImg.src = cardImage(game.trumpCard);
            trumpUnderImg.alt = `Trump card: ${cardName(game.trumpCard)}`;
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
        const glyph = document.createElement("span");
        glyph.className = "pill-role";
        glyph.setAttribute("aria-hidden", "true");
        glyph.textContent = sym;
        const spoken = document.createElement("span");
        spoken.className = "visually-hidden";
        spoken.textContent = `Trump: ${suitName(game.trumpSuit) || sym}`;
        trumpSuitHud.replaceChildren(glyph, spoken);
        trumpSuitHud.title = `Trump ${sym}`;
        trumpSuitHud.classList.remove("hidden");
    } else if (trumpSuitHud) {
        trumpSuitHud.replaceChildren();
        trumpSuitHud.classList.add("hidden");
    }
    const host = game.players.find(p => p.id === game.hostPlayerId);
    const playersList = game.players
        .map(p => p.id === game.hostPlayerId ? `${p.name} (host)` : p.name)
        .join(", ");
    roleLabel.textContent = `Players: ${playersList || "-"}`;
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
        setControlEnabled(addBotBtn, canAddBot && !isBusy());
    }

    if (game.status === "FINISHED") {
        tableAttackerLabel.textContent = "-";
        tableDefenderLabel.textContent = "-";
    } else if (game.takingCardsInProgress) {
        tableAttackerLabel.textContent = attacker ? attacker.name : "-";
        if (defender) {
            tableDefenderLabel.innerHTML =
                `${escapeHtml(defender.name)}<span class="pill-take" title="Taking cards" aria-hidden="true">⇩</span>`
                + `<span class="visually-hidden">, taking the cards</span>`;
        } else {
            tableDefenderLabel.textContent = "-";
        }
    } else {
        tableAttackerLabel.textContent = attacker ? attacker.name : "-";
        tableDefenderLabel.textContent = defender ? defender.name : "-";
    }
    if (openingLeadHud) {
        const emptyTable = !game.table || game.table.length === 0;
        const showOpening =
            game.status === "IN_PROGRESS" && game.players.length === 4 && emptyTable && attacker;
        if (showOpening) {
            openingLeadHud.textContent =
                `${attacker.name} is attacking; teammates add matching ranks after.`;
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
        const showTags = game.status === "IN_PROGRESS" || game.status === "FINISHED";
        const myTagHtml = showTags && roleTags(me, game) ? ` ${roleTagsHtml(me, game)}` : "";
        mySeatTitle.innerHTML = `${escapeHtml(me.name)} (you)${botBadgeHtml(me)}${myTagHtml}`;
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
    if (!state.gameCode || state.seatInvalid) return false;
    if (state.gameRefreshInFlight) {
        state.gameRefreshQueued = true;
        return state.gameRefreshInFlight;
    }

    const requestedGameCode = state.gameCode;
    const requestedPlayerId = state.playerId;
    const key = sessionKey();
    const request = (async () => {
        try {
            const query = new URLSearchParams({viewerPlayerId: requestedPlayerId}).toString();
            const refreshed = await api(gamePath(requestedGameCode, `?${query}`), "GET");
            if (!isCurrentSession(key)) {
                return false;
            }
            if (!refreshed || refreshed.code !== requestedGameCode || !Array.isArray(refreshed.players)) {
                throw new ApiError(200, "Unexpected response from the server. Please try again.");
            }
            // A stale version is not a failure: the screen already shows something newer.
            applyGameSnapshot(refreshed, key);
            clearError("connection");
            if (showMessage) log("Game refreshed.");
            return true;
        } catch (err) {
            if (!isCurrentSession(key)) {
                return false;
            }
            if (handleSessionError(err, key)) {
                return false;
            }
            // No game on screen yet means the saved seat is still being restored after a reload.
            showError(!state.game
                ? `Could not reconnect to room ${requestedGameCode}: ${err.message} Retrying in the background.`
                : `Connection problem: ${err.message}`, "connection");
            log(`Refresh failed: ${err.message}`);
            return false;
        }
    })();

    state.gameRefreshInFlight = request;
    try {
        return await request;
    } finally {
        if (state.gameRefreshInFlight === request) {
            state.gameRefreshInFlight = null;
        }
        if (state.gameRefreshQueued && state.gameCode && document.visibilityState !== "hidden") {
            state.gameRefreshQueued = false;
            window.setTimeout(() => refreshGame(false), 0);
        } else {
            scheduleGameRefresh();
        }
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

/** True for a GameResponse-shaped value (as opposed to the create/join envelopes or nothing). */
function looksLikeGame(value) {
    return Boolean(value && typeof value === "object" && typeof value.code === "string" && Array.isArray(value.players));
}

/**
 * Runs one user action. At most one runs at a time: while its request is pending, every other
 * activation (double clicks, Enter repeats, drops) is ignored and the action controls render
 * disabled. `fn` may resolve to a game snapshot, which is applied through the same version check
 * as refreshes, so a delayed response can never roll back newer state.
 */
async function runAction(name, fn, trigger = null) {
    if (isBusy()) {
        log(`${name} ignored: ${state.actionInFlight?.name || "Leave"} is still pending.`);
        return false;
    }
    state.actionInFlight = {name};
    state.lastPlayedCard = null;
    clearError();
    const key = sessionKey();
    const previousText = trigger ? trigger.textContent : "";
    if (trigger) {
        trigger.setAttribute("aria-busy", "true");
        trigger.textContent = `${name}…`;
    }
    render();

    let result = null;
    let error = null;
    try {
        result = await fn();
    } catch (err) {
        error = err || new Error("Something went wrong. Please try again.");
    }
    state.actionInFlight = null;
    if (trigger) {
        trigger.removeAttribute("aria-busy");
        trigger.textContent = previousText;
    }

    if (error) {
        log(`${name} failed: ${error.message}`);
        if (handleSessionError(error, key)) {
            return false;
        }
        render();
        showError(`${name}: ${error.message || "Something went wrong. Please try again."}`);
        if (error.status === 409 && isCurrentSession(key)) {
            // The move was rejected against newer server state: resynchronise promptly.
            scheduleGameRefresh(0, true);
        }
        return false;
    }
    if (!(looksLikeGame(result) && applyGameSnapshot(result, key))) {
        render();
    }
    // The action response already carries updated game state.
    // Suppress the immediate websocket-triggered refetch to avoid double roundtrips.
    state.suppressWsRefreshUntilMs = Date.now() + 1200;
    scheduleGameRefresh();
    log(`${name} success.`);
    return true;
}

function beginPolling() {
    scheduleGameRefresh();
    beginHeartbeat();
}

function scheduleGameRefresh(delayOverride = null, replaceExisting = false) {
    if (!state.gameCode || !state.playerId || state.seatInvalid) {
        cancelGameRefreshTimer();
        return;
    }
    const delay = delayOverride ?? gameRefreshDelayMs(state.wsConnected, document.visibilityState);
    if (delay == null) {
        cancelGameRefreshTimer();
        return;
    }
    const dueAt = Date.now() + Math.max(0, Number(delay) || 0);
    if (state.pollTimer
        && !shouldReplaceRefreshTimer(state.pollDueAt, dueAt, replaceExisting)) return;
    cancelGameRefreshTimer();
    const timer = window.setTimeout(() => {
        if (state.pollTimer !== timer) return;
        state.pollTimer = null;
        state.pollDueAt = 0;
        refreshGame(false);
    }, Math.max(0, dueAt - Date.now()));
    state.pollTimer = timer;
    state.pollDueAt = dueAt;
}

function cancelGameRefreshTimer() {
    if (state.pollTimer) clearTimeout(state.pollTimer);
    state.pollTimer = null;
    state.pollDueAt = 0;
}

function stopPolling() {
    cancelGameRefreshTimer();
    state.gameRefreshQueued = false;
    if (state.heartbeatTimer) clearInterval(state.heartbeatTimer);
    state.heartbeatTimer = null;
}

async function sendHeartbeat() {
    if (document.visibilityState === "hidden"
        || !state.gameCode || !state.playerId || state.seatInvalid || state.game?.status === "FINISHED") return;
    const key = sessionKey();
    try {
        await api(gamePath(state.gameCode, "/heartbeat"), "POST", {playerId: state.playerId});
    } catch (err) {
        // Best effort, except that a rejected seat or a vanished room is reported like any request.
        handleSessionError(err, key);
    }
}

function beginHeartbeat() {
    if (state.heartbeatTimer) clearInterval(state.heartbeatTimer);
    state.heartbeatTimer = setInterval(sendHeartbeat, 5 * 60 * 1000);
}

function pauseGameUpdates() {
    stopPolling();
    closeWebSocket();
}

function closeWebSocket() {
    if (state.wsReconnectTimer) {
        clearTimeout(state.wsReconnectTimer);
        state.wsReconnectTimer = null;
    }
    if (state.wsStableTimer) {
        clearTimeout(state.wsStableTimer);
        state.wsStableTimer = null;
    }
    const ws = state.ws;
    state.ws = null;
    state.wsConnected = false;
    state.wsReconnectAttempt = 0;
    if (ws) {
        ws.onopen = null;
        ws.onmessage = null;
        ws.onclose = null;
        ws.onerror = null;
        if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
            ws.close();
        }
    }
}

function scheduleWebSocketReconnect() {
    if (state.wsReconnectTimer || !state.gameCode || state.seatInvalid || document.visibilityState === "hidden") return;
    const delay = reconnectDelayMs(state.wsReconnectAttempt++);
    state.wsReconnectTimer = window.setTimeout(() => {
        state.wsReconnectTimer = null;
        connectWebSocket();
    }, delay);
}

function connectWebSocket() {
    if (!state.gameCode || state.seatInvalid || document.visibilityState === "hidden" || state.wsReconnectTimer) return;
    const current = state.ws;
    if (current && (current.readyState === WebSocket.OPEN || current.readyState === WebSocket.CONNECTING)) {
        return;
    }
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const gameCode = state.gameCode;
    const ws = new WebSocket(`${protocol}//${window.location.host}/ws/games/${encodeURIComponent(gameCode)}`);
    state.ws = ws;
    ws.onopen = () => {
        if (state.ws !== ws || state.gameCode !== gameCode) return;
        state.wsConnected = true;
        if (state.wsStableTimer) clearTimeout(state.wsStableTimer);
        state.wsStableTimer = window.setTimeout(() => {
            state.wsStableTimer = null;
            if (state.ws === ws && ws.readyState === WebSocket.OPEN) {
                state.wsReconnectAttempt = 0;
            }
        }, 5_000);
        scheduleGameRefresh(0, true);
        log("Realtime connected.");
    };
    ws.onmessage = async (event) => {
        if (state.ws !== ws || state.gameCode !== gameCode) return;
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
                updateBotThinkingIndicators();
                return;
            }
            if (typeof data.version === "number" && Number.isFinite(data.version)) {
                msgVersion = data.version;
            }
        } catch (_) {
            msgVersion = null;
        }
        const localVersion = Number(state.game?.version || 0);
        if (msgVersion !== null && msgVersion <= localVersion) {
            return;
        }
        const suppressionRemainingMs = state.suppressWsRefreshUntilMs - Date.now();
        if (suppressionRemainingMs > 0) {
            scheduleGameRefresh(suppressionRemainingMs + 25);
            return;
        }
        await refreshGame(false);
    };
    ws.onclose = () => {
        if (state.ws !== ws) return;
        if (state.wsStableTimer) {
            clearTimeout(state.wsStableTimer);
            state.wsStableTimer = null;
        }
        state.ws = null;
        state.wsConnected = false;
        scheduleGameRefresh(0);
        scheduleWebSocketReconnect();
    };
    ws.onerror = () => {
        if (state.ws !== ws) return;
        state.wsConnected = false;
        scheduleGameRefresh(0);
        log("Realtime lost, fallback to polling.");
        try { ws.close(); } catch (_) { /* close callback schedules reconnect */ }
    };
}

function clearSession() {
    state.gameCode = "";
    state.playerId = "";
    state.playerToken = "";
    state.seatInvalid = false;
    state.game = null;
    state.selectedHandCard = null;
    saveSession();
    stopPolling();
    closeWebSocket();
    render();
    log("Session cleared.");
}

document.getElementById("hostForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    await runAction("Create room", async () => {
        const created = await api("/api/games", "POST", {
            hostName: hostNameInput.value.trim(),
            publicRoom: Boolean(publicRoomInput?.checked)
        });
        adoptCreatedGame(created);
    }, createBtn);
});

if (quickPlayBtn) {
    quickPlayBtn.addEventListener("click", async () => runAction("Quick play", async () => {
        const created = await api("/api/games/quick-play", "POST", {
            hostName: hostNameInput.value.trim()
        });
        adoptCreatedGame(created);
    }, quickPlayBtn));
}

if (shareBtn) {
    shareBtn.addEventListener("click", async () => {
        const inviteUrl = buildInviteUrl(window.location.origin, state.gameCode);
        if (!inviteUrl) return;
        clearError();
        try {
            if (navigator.clipboard?.writeText) {
                await navigator.clipboard.writeText(inviteUrl);
                shareBtn.textContent = "Invite copied";
            } else {
                const copyInput = document.createElement("textarea");
                copyInput.value = inviteUrl;
                copyInput.setAttribute("readonly", "");
                copyInput.style.position = "fixed";
                copyInput.style.opacity = "0";
                document.body.appendChild(copyInput);
                copyInput.select();
                const copied = document.execCommand("copy");
                copyInput.remove();
                if (!copied) throw new Error("Copy failed");
                shareBtn.textContent = "Invite copied";
            }
            window.setTimeout(() => { shareBtn.textContent = "Copy invite"; }, 1800);
        } catch (err) {
            showError("Could not copy the invite. Copy the room code instead.");
        }
    });
}

document.getElementById("joinForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    await runAction("Join game", () => performJoin(null), joinBtn);
});

/**
 * Asks before leaving a game in progress (leaving resets the table for everyone). Native modal
 * dialog: focus stays inside it and Escape cancels. Resolves to true when the player confirms.
 */
function confirmLeave() {
    if (!leaveDialog || typeof leaveDialog.showModal !== "function") {
        return Promise.resolve(window.confirm("Leave this game? It ends the game for everyone at the table."));
    }
    return new Promise(resolve => {
        // Escape closes without a value, so clear any answer left from an earlier opening.
        leaveDialog.returnValue = "";
        leaveDialog.addEventListener("close", () => {
            const confirmed = leaveDialog.returnValue === "leave";
            if (!confirmed && document.activeElement === document.body && !leaveBtn.disabled) {
                leaveBtn.focus();
            }
            resolve(confirmed);
        }, {once: true});
        leaveDialog.showModal();
    });
}

/**
 * Gives up the seat. Only a confirmed departure, or a seat/room the server says is already
 * gone (403/404/410), clears the local session; on network errors or 5xx the seat is kept so
 * the player is not silently orphaned at the table.
 */
async function leaveRoom() {
    const code = state.gameCode;
    const playerId = state.playerId;
    if (!code || !playerId) {
        clearSession();
        return;
    }
    const key = sessionKey();
    const previousText = leaveBtn.textContent;
    state.leaveInFlight = true;
    leaveBtn.setAttribute("aria-busy", "true");
    leaveBtn.textContent = "Leaving…";
    clearError();
    render();
    try {
        await api(gamePath(code, "/leave"), "POST", {playerId});
        if (isCurrentSession(key)) clearSession();
    } catch (err) {
        if (!isCurrentSession(key)) return;
        const kind = sessionErrorKind(err.status);
        if (kind === "room-gone" || kind === "seat-invalid") {
            clearSession();
            const reason = kind === "seat-invalid"
                ? `This browser's seat in room ${code} was no longer valid`
                : err.status === 410 ? `Room ${code} expired due to inactivity` : `Room ${code} no longer exists`;
            showError(`${reason}, so you are back in the lobby.`, "session");
        } else {
            showError(`Leave: ${err.message} You are still in room ${code}.`);
        }
        log(`Leave failed: ${err.message}`);
    } finally {
        state.leaveInFlight = false;
        leaveBtn.removeAttribute("aria-busy");
        leaveBtn.textContent = previousText;
        render();
    }
}

leaveBtn.addEventListener("click", async () => {
    if (state.leaveInFlight || leaveBtn.getAttribute("aria-disabled") === "true") return;
    const inProgress = state.game?.status === "IN_PROGRESS" && !state.seatInvalid;
    if (inProgress && !(await confirmLeave())) return;
    await leaveRoom();
});
if (seatNoticeLobbyBtn) {
    seatNoticeLobbyBtn.addEventListener("click", () => abandonInvalidSeat());
}
if (helpToggleBtn) {
    helpToggleBtn.addEventListener("click", () => {
        state.showGameplayHelp = !state.showGameplayHelp;
        render();
    });
}

startBtn.addEventListener("click", async () => runAction("Start", playerAction("/start", {}, {clearSelection: false})));

if (rematchBtn) {
    rematchBtn.addEventListener("click", async () => runAction("Play again", playerAction("/rematch"), rematchBtn));
}

function requireSelectedCard() {
    if (!state.selectedHandCard) throw new Error("Select a card first.");
    return state.selectedHandCard;
}

attackBtn.addEventListener("click", async () => runAction("Attack", async () => {
    return playerAction("/attack", {card: requireSelectedCard()})();
}));

transferBtn.addEventListener("click", async () => runAction("Transfer", async () => {
    return playerAction("/transfer", {card: requireSelectedCard()})();
}));

defendBtn.addEventListener("click", async () => runAction("Defend", async () => {
    const card = requireSelectedCard();
    const lm = state.game.legalMoves || {};
    const defs = lm.defensesByAttackCard || {};
    const attacksYouCanBeat = Object.keys(defs).filter(atk => (defs[atk] || []).includes(card));
    let target = defendTargetSelect.value;
    if (!attacksYouCanBeat.includes(target)) {
        target = attacksYouCanBeat[0];
    }
    if (!target) throw new Error("No attack card available to defend.");
    return playerAction("/defend", {attackCard: target, defenseCard: card})();
}));

takeBtn.addEventListener("click", async () => runAction("Take cards", playerAction("/take")));

endRoundBtn.addEventListener("click", async () => runAction("End round", playerAction("/end-round")));

if (addBotBtn) {
    addBotBtn.addEventListener("click", async () => runAction("Add bot", playerAction("/bots", {}, {clearSelection: false})));
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

(async function init() {
    const invitedCode = roomCodeFromSearch(window.location.search);
    const hasDifferentInvite = Boolean(invitedCode && invitedCode !== state.gameCode);
    if (hasDifferentInvite) {
        /* Keep the saved session recoverable at /, but do not leave it live behind the invite view. */
        state.gameCode = "";
        state.playerId = "";
        state.playerToken = "";
    }
    if (!hasDifferentInvite && state.gameCode && state.playerId) {
        /*
         * Until the saved seat's first refresh settles, show "Reconnecting…" instead of the lobby:
         * otherwise Quick Play / Create are clickable and would overwrite the saved seat.
         */
        const code = state.gameCode;
        state.reconnecting = true;
        render();
        beginPolling();
        connectWebSocket();
        await refreshGame();
        state.reconnecting = false;
        if (state.seatInvalid && !state.game) {
            abandonInvalidSeat();
            showError(`This browser's seat in room ${code} is no longer valid. Join the room again or start a new game.`, "session");
        }
        render();
        log(state.game ? "Session restored." : "Session not restored.");
    } else {
        if (invitedCode) {
            gameCodeInput.value = invitedCode;
            if (joinHint) {
                joinHint.textContent = `Invite loaded for room ${invitedCode}. Add your name or join as a random guest.`;
            }
            window.setTimeout(() => playerNameInput.focus(), 0);
        }
        render();
    }
})();
