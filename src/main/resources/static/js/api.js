/*
 * Talking to the server: the saved seat whose token authorises requests, the JSON request
 * helper and its error objects.
 */
import {apiErrorMessage, parseJsonBody} from "./logic.js";

const STORAGE_KEYS = ["durak_game_code", "durak_player_id", "durak_player_token"];

/*
 * Per-tab seat (sessionStorage) so a new tab can stay on the main lobby and see Open tables
 * while another tab hosts a game. A seat is only usable with its secret token: a saved code and
 * player id without one is no session at all.
 */
function loadSavedSeat() {
    const [gameCode, playerId, playerToken] = STORAGE_KEYS.map(key => sessionStorage.getItem(key) || "");
    if (gameCode && playerId && playerToken) return {gameCode, playerId, playerToken};
    if (gameCode || playerId || playerToken) {
        console.warn("Durak: ignoring an incomplete saved seat (no player token).");
        for (const key of STORAGE_KEYS) sessionStorage.removeItem(key);
    }
    return {gameCode: "", playerId: "", playerToken: ""};
}

/** This tab's seat: which room, as whom, and the secret token that proves it. */
export const seat = loadSavedSeat();

export function saveSeat() {
    const values = [seat.gameCode, seat.playerId, seat.playerToken];
    STORAGE_KEYS.forEach((key, i) => sessionStorage.setItem(key, values[i] || ""));
}

/** Identifies the seat a request was made for, so late responses cannot leak into another session. */
export function sessionKey() {
    return `${seat.gameCode}\u0000${seat.playerId}`;
}

export function isCurrentSession(key) {
    return Boolean(seat.gameCode) && key === sessionKey();
}

/* Capability token proving we own seat.playerId. There is no fallback: no token, no seat. */
function authHeaders() {
    return seat.playerToken ? {"X-Durak-Token": seat.playerToken} : {};
}

/** Every game request path goes through here so a stored or typed code is always encoded. */
export function gamePath(code, suffix = "") {
    return `/api/games/${encodeURIComponent(code)}${suffix}`;
}

const API_TIMEOUT_MS = 20_000;

/** A failed request; `status` is the HTTP status, or 0 when the server could not be reached. */
export class ApiError extends Error {
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
export async function api(path, method, body) {
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
