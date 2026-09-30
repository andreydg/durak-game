/*
 * The one reconnecting WebSocket helper, used for the Open tables stream (lobby.js) and for the
 * game's invalidations (sync.js).
 */
import {reconnectDelayMs} from "./logic.js";

/**
 * Keeps one WebSocket connected while `canConnect()` holds: after a close it reconnects with
 * bounded exponential backoff (reconnectDelayMs), and the backoff resets once a connection has
 * stayed open for `stableAfterMs`. Events from a socket that has been replaced are ignored.
 *
 *   url()                          where to connect, read for each new connection
 *   onOpen(url)                    the connection opened
 *   onMessage(event, url)          a message arrived
 *   onDown("close"|"error", url)   the connection failed; a close is followed by a reconnect
 */
export function createReconnectingSocket({url, canConnect, onOpen, onMessage, onDown, stableAfterMs = 5_000}) {
    let ws = null;
    let reconnectTimer = null;
    let stableTimer = null;
    let attempt = 0;

    function clearStableTimer() {
        if (stableTimer) {
            clearTimeout(stableTimer);
            stableTimer = null;
        }
    }

    function scheduleReconnect() {
        if (reconnectTimer || !canConnect()) return;
        const delay = reconnectDelayMs(attempt++);
        reconnectTimer = window.setTimeout(() => {
            reconnectTimer = null;
            connect();
        }, delay);
    }

    function connect() {
        if (!canConnect() || reconnectTimer) return;
        if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
        const target = url();
        const socket = new WebSocket(target);
        ws = socket;
        socket.onopen = () => {
            if (ws !== socket) return;
            clearStableTimer();
            stableTimer = window.setTimeout(() => {
                stableTimer = null;
                if (ws === socket && socket.readyState === WebSocket.OPEN) attempt = 0;
            }, stableAfterMs);
            onOpen?.(target);
        };
        socket.onmessage = event => {
            if (ws !== socket) return;
            onMessage?.(event, target);
        };
        socket.onclose = () => {
            if (ws !== socket) return;
            clearStableTimer();
            ws = null;
            onDown?.("close", target);
            scheduleReconnect();
        };
        socket.onerror = () => {
            if (ws !== socket) return;
            onDown?.("error", target);
            try { socket.close(); } catch (_) { /* the close callback schedules the reconnect */ }
        };
    }

    /** Disconnects for good (until the next connect()) and forgets the backoff. */
    function close() {
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
            reconnectTimer = null;
        }
        clearStableTimer();
        const socket = ws;
        ws = null;
        attempt = 0;
        if (socket) {
            socket.onopen = null;
            socket.onmessage = null;
            socket.onclose = null;
            socket.onerror = null;
            if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
                socket.close();
            }
        }
    }

    return {connect, close};
}

/** ws:// or wss:// URL for a path on this origin. */
export function socketUrl(path) {
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    return `${protocol}//${window.location.host}${path}`;
}
