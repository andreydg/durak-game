/*
 * Entry point: wires the modules together and to the page, then restores the saved seat or
 * shows the lobby.
 *
 *   logic.js    pure helpers (unit-tested)       state.js   shared UI state
 *   dom.js      page elements                     api.js     requests, errors, the saved seat
 *   socket.js   reconnecting WebSocket helper     view.js    rendering and focus
 *   lobby.js    Open tables                        sync.js    game refreshes, heartbeat, socket
 *   actions.js  user actions and seat lifecycle    main.js    this wiring
 */
import {roomCodeFromSearch} from "./logic.js";
import {seat} from "./api.js";
import {state} from "./state.js";
import {el} from "./dom.js";
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

el.lobbyGameList?.addEventListener("click", (e) => {
    const btn = e.target.closest(".lobby-list-join");
    if (!btn || !el.lobbyGameList.contains(btn)) return;
    const code = btn.getAttribute("data-code");
    if (code) runAction("Join game", () => performJoin(code), null, {scope: "lobby"});
});

/* ---- room and table ---- */

el.shareBtn?.addEventListener("click", copyInvite);
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

/* Hand cards are dragged with HTML5 drag and drop onto the battle row (or onto one pair). */
el.battleCards.addEventListener("dragover", (e) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
    const pair = e.target.closest(".battle-pair");
    setBattlePairDropHover(pair);
    /* Row highlight when not over a specific pair (attack / transfer onto open felt). */
    el.battleCards.classList.toggle("battle-drop-target", !pair);
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

/* ---- hidden tabs pause everything; visible again reconnects and catches up ---- */

document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") {
        stopLobbyListPolling();
        pauseGameUpdates();
        return;
    }
    syncLobbyListPolling();
    if (seat.gameCode && !state.seatInvalid) {
        beginPolling();
        sendHeartbeat();
        connectGameSocket();
        refreshGame();
    }
});

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
            window.setTimeout(() => el.playerNameInput.focus(), 0);
        }
        render();
    }
})();
