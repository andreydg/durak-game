/*
 * What the player does and what happens to the seat: the one-action-at-a-time guard, applying
 * game snapshots through the version check, joining, creating, leaving and the table moves.
 */
import {
    buildInviteUrl,
    chooseDefenceTarget,
    chooseDropAction,
    describeTransition,
    isGameSnapshot,
    leaveFailureMessage,
    normalizeRoomCode,
    roomGoneMessage,
    searchWithoutRoomParam,
    seatProblem,
    sessionErrorKind,
    shouldApplySnapshot
} from "./logic.js";
import {ApiError, api, gamePath, isCurrentSession, saveSeat, seat, sessionKey} from "./api.js";
import {isBusy, state} from "./state.js";
import {el} from "./dom.js";
import {announce, clearError, leaveNeedsConfirmation, log, render, showError} from "./view.js";
import {
    beginPolling,
    closeGameSocket,
    connectGameSocket,
    scheduleGameRefresh,
    stopPolling,
    syncBotThinkingFromGame
} from "./sync.js";

/* ---- game state from the server ---- */

/**
 * The single entry point for game state from the server (refreshes, action responses, joins).
 * Rejects snapshots for another seat or older than the one on screen, then renders.
 */
export function applyGameSnapshot(snapshot, key = sessionKey()) {
    if (!isCurrentSession(key) || !snapshot || snapshot.code !== seat.gameCode) return false;
    if (!shouldApplySnapshot(state.game, snapshot)) {
        log(`Ignored stale game version ${snapshot.version}; current version is ${state.game?.version}.`);
        return false;
    }
    const previous = state.game;
    if (previous && previous.code === snapshot.code && previous.status !== snapshot.status) {
        state.selectedHandCard = null;
    }
    const announcements = describeTransition(previous, snapshot, seat.playerId);
    state.game = snapshot;
    if (announcements.length) announce(announcements.join(" "));
    const me = snapshot.players.find(p => p.id === seat.playerId);
    if (state.selectedHandCard && !(me?.hand || []).includes(state.selectedHandCard)) {
        state.selectedHandCard = null;
    }
    syncBotThinkingFromGame(snapshot);
    const problem = seatProblem(snapshot, seat.playerId);
    if (problem) {
        markSeatInvalid(problem);
    } else {
        render();
    }
    return true;
}

/**
 * Handles failures that invalidate the saved seat. Returns true when the error was consumed:
 * a room that is gone (404 on a read, 410) ends the session, 403 means this browser's seat can no
 * longer act. A 404 from a move ("verify") may just be a refused move, so the room is re-read
 * right away and that read decides. `source` is "read", "leave" or "move" (see sessionErrorKind).
 */
export function handleSessionError(err, key = sessionKey(), source = "read") {
    if (!err || !isCurrentSession(key)) return false;
    const kind = sessionErrorKind(err.status, source);
    const code = seat.gameCode;
    if (kind === "verify") {
        scheduleGameRefresh(0, true);
        return false;
    }
    if (kind === "room-gone") {
        clearSession();
        showError(roomGoneMessage(err.status, code), "session");
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
export function markSeatInvalid(reason) {
    if (!seat.gameCode) return;
    if (!state.seatInvalid) {
        console.warn(`Durak: this browser's seat in room ${seat.gameCode} is no longer valid (${reason}).`);
    }
    state.seatInvalid = true;
    state.selectedHandCard = null;
    stopPolling();
    closeGameSocket();
    render();
}

/** "Back to lobby" from an invalid seat: forget it locally and offer to join the room again. */
export function abandonInvalidSeat() {
    const code = seat.gameCode;
    clearSession();
    clearError();
    if (code) {
        el.gameCodeInput.value = code;
        if (el.joinHint) {
            el.joinHint.textContent = `Room ${code} is filled in. Join again for a new seat, or create a room.`;
        }
    }
}

export function clearSession() {
    seat.gameCode = "";
    seat.playerId = "";
    seat.playerToken = "";
    state.seatInvalid = false;
    state.game = null;
    state.selectedHandCard = null;
    saveSeat();
    stopPolling();
    closeGameSocket();
    render();
    log("Session cleared.");
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

/** Takes over the seat returned by create/join/quick play and shows its game. */
function adoptSession(game, playerId, playerToken) {
    if (!isGameSnapshot(game) || !playerId || !playerToken) {
        throw new ApiError(200, "Unexpected response from the server. Please try again.");
    }
    closeGameSocket();
    seat.gameCode = game.code;
    seat.playerId = String(playerId);
    seat.playerToken = String(playerToken);
    state.seatInvalid = false;
    state.game = null;
    state.selectedHandCard = null;
    saveSeat();
    clearConsumedInvite();
    applyGameSnapshot(game);
    beginPolling();
    connectGameSocket();
}

/* ---- lobby: join, create, quick play ---- */

export async function performJoin(roomCode) {
    const raw = roomCode != null && String(roomCode).trim() !== ""
        ? String(roomCode).trim()
        : el.gameCodeInput.value.trim();
    if (!raw) throw new Error("Enter a room code.");
    const code = normalizeRoomCode(raw);
    if (!code) {
        throw new Error("That room code is not valid. Room codes have 6 letters and digits (no 0, 1, I or O).");
    }
    const joined = await api(gamePath(code, "/join"), "POST", {playerName: el.playerNameInput.value.trim()});
    adoptSession(joined?.game, joined?.playerId, joined?.playerToken);
}

export async function createRoom() {
    const created = await api("/api/games", "POST", {
        hostName: el.hostNameInput.value.trim(),
        publicRoom: Boolean(el.publicRoomInput?.checked)
    });
    adoptSession(created?.game, created?.hostPlayerId, created?.playerToken);
}

export async function quickPlay() {
    const created = await api("/api/games/quick-play", "POST", {
        hostName: el.hostNameInput.value.trim()
    });
    adoptSession(created?.game, created?.hostPlayerId, created?.playerToken);
}

/* ---- the one-action-at-a-time guard ---- */

/**
 * Runs one user action. At most one runs at a time: while its request is pending, every other
 * activation (double clicks, Enter repeats, drops) is ignored and the action controls render
 * disabled. `fn` may resolve to a game snapshot, which is applied through the same version check
 * as refreshes, so a delayed response can never roll back newer state.
 * `scope` "seat" (default) is a move at the current table, whose 403/404/410 speak about that
 * seat; "lobby" (create/join) requests target another room, so their errors are only shown.
 */
export async function runAction(name, fn, trigger = null, {scope = "seat"} = {}) {
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
        if (scope === "seat" && handleSessionError(error, key, "move")) {
            return false;
        }
        render();
        if (sessionKey() !== key) {
            // The player left or switched rooms meanwhile; this failure no longer concerns them.
            return false;
        }
        showError(`${name}: ${error.message || "Something went wrong. Please try again."}`);
        if (scope === "seat" && error.status === 409 && isCurrentSession(key)) {
            // The move was rejected against newer server state: resynchronise promptly.
            scheduleGameRefresh(0, true);
        }
        return false;
    }
    if (!(isGameSnapshot(result) && applyGameSnapshot(result, key))) {
        render();
    }
    // The action response already carries updated game state.
    // Suppress the immediate websocket-triggered refetch to avoid double roundtrips.
    state.suppressWsRefreshUntilMs = Date.now() + 1200;
    scheduleGameRefresh();
    log(`${name} success.`);
    return true;
}

/* ---- moves at the table ---- */

/**
 * Builds a runAction body for a seat action: POSTs {playerId, ...extra} and resolves to the
 * returned game snapshot, which runAction applies through the version check.
 */
function playerAction(suffix, extra = {}, {clearSelection = true} = {}) {
    return async () => {
        state.lastPlayedCard = extra.card || extra.defenseCard || null;
        const game = await api(gamePath(seat.gameCode, suffix), "POST", {playerId: seat.playerId, ...extra});
        if (clearSelection) state.selectedHandCard = null;
        return game;
    };
}

function requireSelectedCard() {
    if (!state.selectedHandCard) throw new Error("Select a card first.");
    return state.selectedHandCard;
}

export const startGame = () => runAction("Start", playerAction("/start", {}, {clearSelection: false}));
export const rematch = () => runAction("Play again", playerAction("/rematch"), el.rematchBtn);
export const addBot = () => runAction("Add bot", playerAction("/bots", {}, {clearSelection: false}));
export const takeCards = () => runAction("Take cards", playerAction("/take"));
export const endRound = () => runAction("End round", playerAction("/end-round"));

export const attack = () => runAction("Attack", async () => {
    return playerAction("/attack", {card: requireSelectedCard()})();
});

export const transfer = () => runAction("Transfer", async () => {
    return playerAction("/transfer", {card: requireSelectedCard()})();
});

export const defend = () => runAction("Defend", async () => {
    const card = requireSelectedCard();
    const target = chooseDefenceTarget(state.game.legalMoves?.defensesByAttackCard, card, [el.defendTargetSelect.value]);
    if (!target) throw new Error("No attack card available to defend.");
    return playerAction("/defend", {attackCard: target, defenseCard: card})();
});

/**
 * Drag card to battle: transfer first, then attack, then defend (against the pair it was dropped
 * on when it can beat that one, else the "Attack card to beat" menu's choice).
 */
export async function playCardToTable(cardCode, preferredAttackCard) {
    const game = state.game;
    if (!game || game.status !== "IN_PROGRESS" || !cardCode || isBusy() || state.seatInvalid) return;
    const move = chooseDropAction(game.legalMoves, cardCode, [preferredAttackCard, el.defendTargetSelect.value]);
    if (move?.kind === "transfer") {
        await runAction("Transfer", playerAction("/transfer", {card: cardCode}));
    } else if (move?.kind === "attack") {
        await runAction("Attack", playerAction("/attack", {card: cardCode}));
    } else if (move?.kind === "defend") {
        await runAction("Defend", playerAction("/defend", {attackCard: move.target, defenseCard: cardCode}));
    } else {
        log(`Cannot play ${cardCode} to the table right now.`);
    }
}

/* ---- leaving ---- */

/**
 * Asks before leaving a game in progress (leaving resets the table for everyone). Native modal
 * dialog: focus stays inside it and Escape cancels. Resolves to true when the player confirms.
 */
function confirmLeave() {
    const dialog = el.leaveDialog;
    if (!dialog || typeof dialog.showModal !== "function") {
        return Promise.resolve(window.confirm("Leave this game? It ends the game for everyone at the table."));
    }
    return new Promise(resolve => {
        // Escape closes without a value, so clear any answer left from an earlier opening.
        dialog.returnValue = "";
        dialog.addEventListener("close", () => {
            const confirmed = dialog.returnValue === "leave";
            if (!confirmed && document.activeElement === document.body && !el.leaveBtn.disabled) {
                el.leaveBtn.focus();
            }
            resolve(confirmed);
        }, {once: true});
        dialog.showModal();
    });
}

/**
 * Gives up the seat. Only a confirmed departure, or a seat/room the server says is already
 * gone (403/404/410), clears the local session; on network errors or 5xx the seat is kept so
 * the player is not silently orphaned at the table.
 */
async function leaveRoom() {
    const code = seat.gameCode;
    const playerId = seat.playerId;
    if (!code || !playerId) {
        clearSession();
        return;
    }
    const key = sessionKey();
    const leaveBtn = el.leaveBtn;
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
        const kind = sessionErrorKind(err.status, "leave");
        if (kind === "room-gone" || kind === "seat-invalid") {
            clearSession();
            showError(leaveFailureMessage(kind, err.status, code, err.message), "session");
        } else {
            showError(leaveFailureMessage(kind, err.status, code, err.message));
        }
        log(`Leave failed: ${err.message}`);
    } finally {
        state.leaveInFlight = false;
        leaveBtn.removeAttribute("aria-busy");
        leaveBtn.textContent = previousText;
        render();
    }
}

/** The Leave button: confirm while a game is in progress, then leave. */
export async function requestLeave() {
    if (state.leaveInFlight || el.leaveBtn.getAttribute("aria-disabled") === "true") return;
    if (leaveNeedsConfirmation() && !(await confirmLeave())) return;
    await leaveRoom();
}

/* ---- sharing ---- */

/** Copies the room's invite link (clipboard API, else a hidden textarea and execCommand). */
export async function copyInvite() {
    const shareBtn = el.shareBtn;
    const inviteUrl = buildInviteUrl(window.location.origin, seat.gameCode);
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
}
