/*
 * Open tables: the list of public waiting rooms on the home page (and in a public waiting room),
 * kept current by the lobby socket's invalidations with a slow HTTP health refresh behind it.
 */
import {lobbyInvalidation, lobbyRefreshDelayMs, lobbyRowsHtml} from "./logic.js";
import {seat} from "./api.js";
import {state} from "./state.js";
import {el} from "./dom.js";
import {createReconnectingSocket, socketUrl} from "./socket.js";
import {log, syncBusyControls} from "./view.js";

const lobby = {
    active: false,
    listTimer: null,
    listDueAt: 0,
    eventTimer: null,
    fetchInFlight: null,
    refreshQueued: false,
    fetchFailures: 0,
    socketConnected: false,
    stream: {streamId: "", revision: -1}
};

const socket = createReconnectingSocket({
    url: () => socketUrl("/ws/lobbies"),
    canConnect: () => lobby.active && document.visibilityState !== "hidden",
    onOpen: () => log("Open tables realtime transport connected."),
    onMessage: event => {
        try {
            const invalidation = lobbyInvalidation(JSON.parse(event.data || "{}"), lobby.stream);
            if (!invalidation) return;
            lobby.stream = {streamId: invalidation.streamId, revision: invalidation.revision};
            lobby.socketConnected = true;
            scheduleLobbyListPoll(null, true);
            log("Open tables realtime connected.");
            if (invalidation.changed) queueLobbyRefresh(invalidation.delayMs);
        } catch (_) {
            // Ignore malformed invalidations; the health refresh remains authoritative.
        }
    },
    onDown: reason => {
        lobby.socketConnected = false;
        scheduleLobbyListPoll();
        if (reason === "error") log("Open tables realtime lost; using fallback refreshes.");
    }
});

export async function refreshLobbyLists() {
    if (lobby.fetchInFlight) {
        lobby.refreshQueued = true;
        return lobby.fetchInFlight;
    }

    const request = (async () => {
        try {
            const res = await fetch("/api/lobbies", {cache: "no-store"});
            if (!res.ok) throw new Error("bad");
            const rows = await res.json();

            const emptyHome = "<p class=\"lobby-list-empty muted\">No open tables yet. Create one above or enter a code.</p>";
            const emptyInRoom = "<p class=\"lobby-list-empty muted\">No lobby tables returned. Try Refresh or wait a moment.</p>";

            /* Always fill #lobbyGameList when data arrives; do not gate on lobbyView visibility (async fetch can race with show/hide). */
            if (el.lobbyGameList) {
                const focusedCode = el.lobbyGameList.contains(document.activeElement)
                    ? document.activeElement.getAttribute("data-code")
                    : null;
                el.lobbyGameList.innerHTML = rows.length ? lobbyRowsHtml(rows, true, null) : emptyHome;
                if (focusedCode !== null) {
                    // The list was rebuilt under a keyboard user: keep them on the same table's
                    // Join button, or the first one, or the list heading if the list emptied.
                    const buttons = [...el.lobbyGameList.querySelectorAll(".lobby-list-join")];
                    const again = buttons.find(btn => btn.getAttribute("data-code") === focusedCode)
                        || buttons[0]
                        || document.getElementById("openTablesHeading");
                    again?.focus();
                }
                // After restoring focus, so a pending join keeps its (focused) button aria-disabled.
                syncBusyControls();
            }
            if (el.gameLobbyGameList) {
                const code = seat.gameCode || "";
                const inRoom = el.gameOpenTablesWrap && !el.gameOpenTablesWrap.classList.contains("hidden");
                el.gameLobbyGameList.innerHTML = rows.length
                    ? lobbyRowsHtml(rows, false, code)
                    : (inRoom ? emptyInRoom : "");
            }
            return true;
        } catch (error) {
            const err = "<p class=\"lobby-list-empty muted\">Could not load open tables. Try refreshing the page.</p>";
            try {
                if (el.lobbyGameList) el.lobbyGameList.innerHTML = err;
                if (el.gameLobbyGameList) el.gameLobbyGameList.innerHTML = err;
            } catch (_) {
                // A broken renderer must not keep the single-flight lock held forever.
            }
            log(`Open tables refresh failed: ${error?.message || "unknown error"}`);
            return false;
        }
    })();

    lobby.fetchInFlight = request;
    let succeeded = false;
    try {
        succeeded = await request;
        return succeeded;
    } finally {
        if (lobby.fetchInFlight === request) {
            lobby.fetchInFlight = null;
        }
        lobby.fetchFailures = succeeded ? 0 : Math.min(lobby.fetchFailures + 1, 3);
        scheduleLobbyListPoll(null, true);

        if (lobby.refreshQueued) {
            lobby.refreshQueued = false;
            queueLobbyRefresh(0);
        }
    }
}

/** Open tables matter on the home page and in a public waiting room, not during a game. */
function shouldPollOpenTables() {
    if (state.reconnecting) {
        return false;
    }
    if (!seat.gameCode || !seat.playerId || !state.game) {
        return true;
    }
    return state.game.status === "LOBBY" && state.game.publicRoom !== false;
}

/** Starts or stops the list updates to match the current view (called after every render). */
export function syncLobbyListPolling() {
    const shouldRun = shouldPollOpenTables() && document.visibilityState !== "hidden";
    if (shouldRun) {
        const becameActive = !lobby.active;
        lobby.active = true;
        socket.connect();
        if (becameActive) {
            refreshLobbyLists();
        } else if (!lobby.listTimer && !lobby.fetchInFlight) {
            scheduleLobbyListPoll();
        }
    } else {
        stopLobbyListPolling();
    }
}

function scheduleLobbyListPoll(delayOverride = null, replaceExisting = false) {
    if (!lobby.active || !shouldPollOpenTables()) return;
    const delay = delayOverride ?? lobbyRefreshDelayMs(
        lobby.socketConnected,
        lobby.fetchFailures,
        document.visibilityState
    );
    if (delay == null) return;
    const dueAt = Date.now() + delay;
    if (lobby.listTimer && !replaceExisting && lobby.listDueAt <= dueAt) {
        return;
    }
    if (lobby.listTimer) {
        clearTimeout(lobby.listTimer);
    }
    lobby.listDueAt = dueAt;
    lobby.listTimer = window.setTimeout(() => {
        lobby.listTimer = null;
        lobby.listDueAt = 0;
        refreshLobbyLists();
    }, delay);
}

function queueLobbyRefresh(delay = 75) {
    if (!lobby.active || document.visibilityState === "hidden") return;
    if (lobby.eventTimer) clearTimeout(lobby.eventTimer);
    lobby.eventTimer = window.setTimeout(() => {
        lobby.eventTimer = null;
        refreshLobbyLists();
    }, delay);
}

export function stopLobbyListPolling() {
    lobby.active = false;
    lobby.refreshQueued = false;
    if (lobby.listTimer) {
        clearTimeout(lobby.listTimer);
        lobby.listTimer = null;
        lobby.listDueAt = 0;
    }
    if (lobby.eventTimer) {
        clearTimeout(lobby.eventTimer);
        lobby.eventTimer = null;
    }
    lobby.socketConnected = false;
    socket.close();
}
