/*
 * Keeping the seat's game current: invalidations from the game socket, HTTP refreshes (a fast
 * fallback while the socket is down, a slow health check while it is up) and the heartbeat.
 */
import {gameRefreshDelayMs, isGameSnapshot, refreshFailureMessage, shouldReplaceRefreshTimer} from "./logic.js";
import {ApiError, api, gamePath, isCurrentSession, seat, sessionKey} from "./api.js";
import {state} from "./state.js";
import {createReconnectingSocket, socketUrl} from "./socket.js";
import {clearError, log, showError, updateBotThinkingIndicators} from "./view.js";

/*
 * What to do with a fresh snapshot or a failed request. actions.js owns both (and imports this
 * module), so main.js hands them over through configureSync() instead of a circular import.
 */
const handlers = {
    applySnapshot: () => false,
    handleSessionError: () => false
};

export function configureSync({applySnapshot, handleSessionError}) {
    handlers.applySnapshot = applySnapshot;
    handlers.handleSessionError = handleSessionError;
}

const sync = {
    pollTimer: null,
    pollDueAt: 0,
    heartbeatTimer: null,
    refreshInFlight: null,
    refreshQueued: false,
    socketConnected: false
};

const gameSocketUrl = code => socketUrl(`/ws/games/${encodeURIComponent(code)}`);

const socket = createReconnectingSocket({
    url: () => gameSocketUrl(seat.gameCode),
    canConnect: () => Boolean(seat.gameCode) && !state.seatInvalid && document.visibilityState !== "hidden",
    onOpen: url => {
        if (url !== gameSocketUrl(seat.gameCode)) return;
        sync.socketConnected = true;
        scheduleGameRefresh(0, true);
        log("Realtime connected.");
    },
    onMessage: (event, url) => {
        if (url !== gameSocketUrl(seat.gameCode)) return;
        handleGameMessage(event.data);
    },
    onDown: reason => {
        sync.socketConnected = false;
        scheduleGameRefresh(0);
        if (reason === "error") log("Realtime lost, fallback to polling.");
    }
});

export function connectGameSocket() {
    socket.connect();
}

export function closeGameSocket() {
    sync.socketConnected = false;
    socket.close();
}

/** BOT_THINKING updates the thinking notes; anything newer than the shown game triggers a read. */
async function handleGameMessage(raw) {
    let msgVersion = null;
    try {
        const data = JSON.parse(raw || "{}");
        if (data.type === "BOT_THINKING" && data.playerId) {
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
    await refreshGame();
}

/** Reads the seat's game and applies it; single-flight, with one queued follow-up read. */
export async function refreshGame() {
    if (!seat.gameCode || state.seatInvalid) return false;
    if (sync.refreshInFlight) {
        sync.refreshQueued = true;
        return sync.refreshInFlight;
    }

    const requestedGameCode = seat.gameCode;
    const requestedPlayerId = seat.playerId;
    const key = sessionKey();
    const request = (async () => {
        try {
            const query = new URLSearchParams({viewerPlayerId: requestedPlayerId}).toString();
            const refreshed = await api(gamePath(requestedGameCode, `?${query}`), "GET");
            if (!isCurrentSession(key)) {
                return false;
            }
            if (!isGameSnapshot(refreshed) || refreshed.code !== requestedGameCode) {
                throw new ApiError(200, "Unexpected response from the server. Please try again.");
            }
            // A stale version is not a failure: the screen already shows something newer.
            handlers.applySnapshot(refreshed, key);
            clearError("connection");
            return true;
        } catch (err) {
            if (!isCurrentSession(key)) {
                return false;
            }
            if (handlers.handleSessionError(err, key)) {
                return false;
            }
            // No game on screen yet means the saved seat is still being restored after a reload.
            showError(refreshFailureMessage(Boolean(state.game), requestedGameCode, err.message), "connection");
            log(`Refresh failed: ${err.message}`);
            return false;
        }
    })();

    sync.refreshInFlight = request;
    try {
        return await request;
    } finally {
        if (sync.refreshInFlight === request) {
            sync.refreshInFlight = null;
        }
        if (sync.refreshQueued && seat.gameCode && document.visibilityState !== "hidden") {
            sync.refreshQueued = false;
            window.setTimeout(() => refreshGame(), 0);
        } else {
            scheduleGameRefresh();
        }
    }
}

/** Bot notes from a snapshot replace the ones from socket events. */
export function syncBotThinkingFromGame(game) {
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

export function beginPolling() {
    scheduleGameRefresh();
    beginHeartbeat();
}

export function scheduleGameRefresh(delayOverride = null, replaceExisting = false) {
    if (!seat.gameCode || !seat.playerId || state.seatInvalid) {
        cancelGameRefreshTimer();
        return;
    }
    const delay = delayOverride ?? gameRefreshDelayMs(sync.socketConnected, document.visibilityState);
    if (delay == null) {
        cancelGameRefreshTimer();
        return;
    }
    const dueAt = Date.now() + Math.max(0, Number(delay) || 0);
    if (sync.pollTimer
        && !shouldReplaceRefreshTimer(sync.pollDueAt, dueAt, replaceExisting)) return;
    cancelGameRefreshTimer();
    const timer = window.setTimeout(() => {
        if (sync.pollTimer !== timer) return;
        sync.pollTimer = null;
        sync.pollDueAt = 0;
        refreshGame();
    }, Math.max(0, dueAt - Date.now()));
    sync.pollTimer = timer;
    sync.pollDueAt = dueAt;
}

function cancelGameRefreshTimer() {
    if (sync.pollTimer) clearTimeout(sync.pollTimer);
    sync.pollTimer = null;
    sync.pollDueAt = 0;
}

export function stopPolling() {
    cancelGameRefreshTimer();
    sync.refreshQueued = false;
    if (sync.heartbeatTimer) clearInterval(sync.heartbeatTimer);
    sync.heartbeatTimer = null;
}

export async function sendHeartbeat() {
    if (document.visibilityState === "hidden"
        || !seat.gameCode || !seat.playerId || state.seatInvalid || state.game?.status === "FINISHED") return;
    const key = sessionKey();
    try {
        await api(gamePath(seat.gameCode, "/heartbeat"), "POST", {playerId: seat.playerId});
    } catch (err) {
        // Best effort, except that a rejected seat or a vanished room is reported like any request.
        handlers.handleSessionError(err, key, "move");
    }
}

function beginHeartbeat() {
    if (sync.heartbeatTimer) clearInterval(sync.heartbeatTimer);
    sync.heartbeatTimer = setInterval(sendHeartbeat, 5 * 60 * 1000);
}

/** Hidden tab: no reads, no heartbeat, no socket until it is visible again. */
export function pauseGameUpdates() {
    stopPolling();
    closeGameSocket();
}
