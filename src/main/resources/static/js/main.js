/*
 * Entry point: wires the modules together and to the page, then restores the saved seat or
 * shows the lobby.
 *
 *   logic.js    pure helpers (unit-tested)       state.js   shared UI state
 *   dom.js      page elements                     api.js     requests, errors, the saved seat
 *   socket.js   reconnecting WebSocket helper     view.js    rendering and focus
 *   lobby.js    Open tables                        sync.js    game refreshes, heartbeat, socket
 *   actions.js  user actions and seat lifecycle    touchdrag.js  dragging cards with a finger
 *   main.js     this wiring
 */
import {chooseDropAction, msUntilIdle, roomCodeFromSearch} from "./logic.js";
import {seat} from "./api.js";
import {isBusy, state} from "./state.js";
import {el} from "./dom.js";
import {installTouchDrag} from "./touchdrag.js";
import {
    clearBattleDropUi,
    log,
    onRendered,
    render,
    renderActionState,
    setBattlePairDropHover,
    showDebugPanel,
    showError
} from "./view.js";
import {refreshLobbyLists, stopLobbyListPolling, syncLobbyListPolling} from "./lobby.js";
import {
    beginPolling,
    configureSync,
    connectGameSocket,
    gameUpdatesIdlePaused,
    pauseGameUpdates,
    refreshGame,
    sendHeartbeat,
    stopPolling
} from "./sync.js";
import {
    abandonInvalidSeat,
    addBot,
    applyGameSnapshot,
    attack,
    copyInvite,
    copyRoomCode,
    createRoom,
    defend,
    endRound,
    handleSessionError,
    performJoin,
    playCardToTable,
    quickPlay,
    rematch,
    requestLeave,
    runAction,
    startGame,
    takeCards,
    transfer
} from "./actions.js";

configureSync({applySnapshot: applyGameSnapshot, handleSessionError});
onRendered(syncLobbyListPolling);
onRendered(syncIdlePause);
showDebugPanel();

/* ---- lobby ---- */

el.hostForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    await runAction("Create room", createRoom, el.createBtn, {scope: "lobby"});
});

el.quickPlayBtn?.addEventListener("click", async () =>
    runAction("Quick play", quickPlay, el.quickPlayBtn, {scope: "lobby"}));

el.joinForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    await runAction("Join game", () => performJoin(null), el.joinBtn, {scope: "lobby"});
});

/* The shared name field belongs to neither form: Enter there joins once a room code is filled in. */
el.hostNameInput.addEventListener("keydown", (event) => {
    if (event.key !== "Enter" || event.isComposing || el.hostNameInput.form || !el.gameCodeInput.value.trim()) return;
    event.preventDefault();
    el.joinBtn.click();
});

el.lobbyGameList?.addEventListener("click", (e) => {
    const btn = e.target.closest(".lobby-list-join");
    if (!btn || !el.lobbyGameList.contains(btn)) return;
    const code = btn.getAttribute("data-code");
    if (code) runAction("Join game", () => performJoin(code), null, {scope: "lobby"});
});

/* ---- room and table ---- */

el.shareBtn?.addEventListener("click", copyInvite);
el.copyCodeBtn?.addEventListener("click", copyRoomCode);
// Older cached scripts leave this new control hidden rather than offering a dead button.
el.copyCodeBtn?.classList.remove("hidden");
el.leaveBtn.addEventListener("click", requestLeave);
el.seatNoticeLobbyBtn?.addEventListener("click", () => abandonInvalidSeat());
el.helpToggleBtn?.addEventListener("click", () => {
    state.showGameplayHelp = !state.showGameplayHelp;
    render();
});

el.startBtn.addEventListener("click", startGame);
el.rematchBtn?.addEventListener("click", rematch);
el.addBotBtn?.addEventListener("click", addBot);
el.attackBtn.addEventListener("click", attack);
el.transferBtn.addEventListener("click", transfer);
el.defendBtn.addEventListener("click", defend);
el.takeBtn.addEventListener("click", takeCards);
el.endRoundBtn.addEventListener("click", endRound);
el.defendTargetSelect.addEventListener("change", () => {
    if (state.game) renderActionState(state.game);
});

/*
 * Hand cards are dragged with HTML5 drag and drop onto the battle row (or onto one pair). The
 * outline shows what the drop will do, decided as playCardToTable decides it: the pair the card
 * will defend, else the whole row for a transfer or attack. Dragover events can read a drag's
 * types but not its data, so a drag from the hand also carries its card in a type of its own.
 * Nothing is remembered between drags: a drag that started anywhere else (outside the hand or
 * the page) has no such type, even if an earlier drag's card was removed before its drag ended.
 */
const HAND_CARD_TYPE = "application/x-durak-hand-card-";
el.myHand.addEventListener("dragstart", (e) => {
    const code = e.defaultPrevented ? "" : e.target.closest?.(".hand-card-btn")?.dataset.cardCode || "";
    if (code) e.dataTransfer.setData(HAND_CARD_TYPE + code.toLowerCase(), code);
});
/** The hand card a drag carries, or "" for any other drag (browsers lower-case data types). */
function draggedHandCard(dataTransfer) {
    const type = Array.from(dataTransfer?.types || []).find(t => t.startsWith(HAND_CARD_TYPE));
    return type ? type.slice(HAND_CARD_TYPE.length).toUpperCase() : "";
}
/** Outlines what dropping hand card `card` would do over `pair` (null: the open felt). */
function previewDrop(card, pair) {
    const attackCard = pair?.dataset?.attackCard?.trim() || "";
    const move = chooseDropAction(state.game?.legalMoves, card, [attackCard, el.defendTargetSelect.value], attackCard);
    const defended = move?.kind === "defend"
        ? [...el.battleCards.querySelectorAll(".battle-pair")].find(p => p.dataset.attackCard === move.target) || null
        : null;
    setBattlePairDropHover(defended);
    el.battleCards.classList.toggle("battle-drop-target", Boolean(move) && !defended);
}
el.battleCards.addEventListener("dragover", (e) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
    const pair = e.target.closest(".battle-pair");
    const card = draggedHandCard(e.dataTransfer);
    if (!card) {
        setBattlePairDropHover(pair);
        el.battleCards.classList.toggle("battle-drop-target", !pair);
        return;
    }
    previewDrop(card, pair);
});
el.battleCards.addEventListener("dragleave", (e) => {
    if (e.relatedTarget && el.battleCards.contains(e.relatedTarget)) return;
    clearBattleDropUi();
});
el.battleCards.addEventListener("drop", async (e) => {
    e.preventDefault();
    const pair = e.target.closest(".battle-pair");
    const preferred = pair?.dataset?.attackCard?.trim() || "";
    clearBattleDropUi();
    const code = (e.dataTransfer.getData("text/plain") || "").trim();
    if (code) await playCardToTable(code, preferred || undefined);
});

/*
 * Phones and tablets drag with a finger (touchdrag.js) to the same targets with the same rules.
 * The whole centre seat counts as the table, a little more forgiving than a mouse needs; over an
 * attack card, that pair is the target, exactly as for a mouse drop.
 */
const touchTable = el.battleCards.closest(".seat.center") || el.battleCards;
installTouchDrag({
    hand: el.myHand,
    canDrag: button => button.draggable && !isBusy() && !state.seatInvalid,
    targetAt(x, y) {
        const hit = document.elementFromPoint(x, y);
        return hit && touchTable.contains(hit) ? {pair: hit.closest(".battle-pair")} : null;
    },
    // A snapshot that arrives while a card is held re-renders these; the outline follows.
    watch: [touchTable, el.myHand],
    preview(code, target) {
        if (target) previewDrop(code, target.pair);
        else clearBattleDropUi();
    },
    drop(code, target) {
        playCardToTable(code, target.pair?.dataset?.attackCard?.trim() || undefined);
    },
    visibleBand() {
        // The table is always above the hand, so a drag only ever needs the page to scroll up:
        // near the sticky room header (or the top of the window) it reveals the table again.
        const header = el.battleCards.closest("#gameView")?.querySelector(".room-header");
        const sticky = header && getComputedStyle(header).position === "sticky" && header.getClientRects().length > 0;
        return {top: sticky ? Math.max(0, header.getBoundingClientRect().bottom) : 0, bottom: Infinity};
    }
});

/* ---- hidden tabs pause everything; visible again reconnects and catches up ---- */

document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") {
        stopLobbyListPolling();
        pauseGameUpdates();
        return;
    }
    // Coming back to the tab is a use of it, even after idling.
    lastActivityAt = Date.now();
    if (state.idlePaused) {
        state.idlePaused = false;
        armIdleTimer();
    }
    resumeLiveUpdates();
});

/** A page shown again, or used again after idling, restarts its lists, reads and sockets. */
function resumeLiveUpdates() {
    syncLobbyListPolling();
    if (seat.gameCode && !state.seatInvalid) {
        beginPolling();
        sendHeartbeat();
        connectGameSocket();
        refreshGame();
    }
}

/*
 * ---- an idle page pauses what nobody is watching ----
 * A visible tab left alone would hold the Open tables socket open on the server around the clock
 * (the client reconnects whenever the server's request timeout ends it). After IDLE_PAUSE_MS with
 * no input, Open tables (home page and waiting room) and a finished game's updates stop, as for a
 * hidden tab; the next interaction picks them up again. A waiting room's or a running game's own
 * updates keep going, since its player may be waiting on someone else.
 */
let lastActivityAt = Date.now();
let idleTimer = 0;

function armIdleTimer() {
    window.clearTimeout(idleTimer);
    idleTimer = window.setTimeout(checkIdle, msUntilIdle(lastActivityAt, Date.now()));
}

function checkIdle() {
    if (msUntilIdle(lastActivityAt, Date.now()) > 0) {
        armIdleTimer();
        return;
    }
    state.idlePaused = true;
    log("No recent activity: Open tables and a finished game's updates are paused until the next interaction.");
    stopLobbyListPolling();
    syncIdlePause();
}

/** While idle, a game that is (or has just become) finished stops its updates too. Runs after every render. */
function syncIdlePause() {
    if (gameUpdatesIdlePaused()) pauseGameUpdates();
}

/** Any input counts as use; once idle, the first one picks everything up again. */
function noteActivity() {
    lastActivityAt = Date.now();
    if (!state.idlePaused) return;
    state.idlePaused = false;
    armIdleTimer();
    log("Activity again: live updates resumed.");
    resumeLiveUpdates();
}

for (const type of ["pointerdown", "pointermove", "keydown", "wheel", "touchstart"]) {
    window.addEventListener(type, noteActivity, {capture: true, passive: true});
}
// The window itself gaining focus (not a focus moved inside the page, which can be the app's own).
window.addEventListener("focus", noteActivity);
armIdleTimer();

/*
 * Test hooks: the Playwright specs (tests/e2e) drive these internals directly, e.g. to force a
 * refresh or stop polling. Modules keep everything else private; this is the whole window API.
 */
Object.assign(window, {refreshGame, refreshLobbyLists, runAction, stopPolling});

(async function init() {
    const invitedCode = roomCodeFromSearch(window.location.search);
    const hasDifferentInvite = Boolean(invitedCode && invitedCode !== seat.gameCode);
    if (hasDifferentInvite) {
        /* Keep the saved session recoverable at /, but do not leave it live behind the invite view. */
        seat.gameCode = "";
        seat.playerId = "";
        seat.playerToken = "";
    }
    if (!hasDifferentInvite && seat.gameCode && seat.playerId) {
        /*
         * Until the saved seat's first refresh settles, show "Reconnecting…" instead of the lobby:
         * otherwise Quick Play / Create are clickable and would overwrite the saved seat.
         */
        const code = seat.gameCode;
        state.reconnecting = true;
        render();
        beginPolling();
        connectGameSocket();
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
            el.gameCodeInput.value = invitedCode;
            if (el.joinHint) {
                el.joinHint.textContent = `Invite loaded for room ${invitedCode}. Add your name or join as a random guest.`;
            }
            const nameField = el.playerNameInput && el.playerNameInput.type !== "hidden"
                ? el.playerNameInput
                : el.hostNameInput;
            window.setTimeout(() => nameField.focus(), 0);
        }
        render();
    }
})();
