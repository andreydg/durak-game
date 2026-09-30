/*
 * Rendering: turns the shared state into the page (lobby, waiting room, table, result), keeps
 * keyboard focus meaningful across re-renders, and shows alerts and announcements.
 */
import {
    actionAvailability,
    actionHint,
    cardName,
    defenceChoice,
    displayStatus,
    escapeHtml,
    fanCountLabel,
    fanLayout,
    focusRecoveryTarget,
    gameResult,
    isCardCode,
    roleDescription,
    roleTags,
    seatRotation,
    sortCardCodesByRank,
    suitName,
    tablePairLabel,
    trumpSuitGlyph,
    viewKey
} from "./logic.js";
import {seat} from "./api.js";
import {isBusy, state} from "./state.js";
import {el} from "./dom.js";

/* ---- feedback: debug log, alert, screen reader announcements ---- */

const debugUi = new URLSearchParams(window.location.search).get("debug") === "1";

export function log(message) {
    if (!debugUi || !el.messages) return;
    const now = new Date().toLocaleTimeString();
    el.messages.textContent = `[${now}] ${message}`;
}

/** The ?debug=1 message panel. */
export function showDebugPanel() {
    if (debugUi && el.messagesPanel) el.messagesPanel.classList.remove("hidden");
}

export function clearError(kind = null) {
    if (!el.appAlert) return;
    if (kind && el.appAlert.dataset.kind !== kind) return;
    el.appAlert.textContent = "";
    delete el.appAlert.dataset.kind;
    el.appAlert.classList.add("hidden");
}

export function showError(message, kind = "action") {
    if (!el.appAlert) return;
    el.appAlert.textContent = message || "Something went wrong. Please try again.";
    el.appAlert.dataset.kind = kind;
    el.appAlert.classList.remove("hidden");
}

/**
 * Polite screen reader announcement. Each message is appended as a new node, which live
 * regions announce reliably even when the same words repeat; old lines are pruned.
 */
export function announce(message) {
    if (!el.liveAnnouncer || !message) return;
    const line = document.createElement("p");
    line.textContent = message;
    el.liveAnnouncer.appendChild(line);
    while (el.liveAnnouncer.childElementCount > 5) {
        el.liveAnnouncer.firstElementChild.remove();
    }
}

/* ---- busy state of the controls ---- */

/**
 * Enables or disables an action control. While a request is pending, the control that has
 * keyboard focus is only marked aria-disabled: a disabled button drops focus to <body>.
 */
export function setControlEnabled(btn, enabled) {
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
    const listButtons = el.lobbyGameList ? [...el.lobbyGameList.querySelectorAll(".lobby-list-join")] : [];
    return [el.quickPlayBtn, el.createBtn, el.joinBtn, ...listButtons];
}

/** Lobby buttons are off while any request is pending; Leave only while leaving. */
export function syncBusyControls() {
    for (const btn of lobbyActionButtons()) setControlEnabled(btn, !isBusy());
    setControlEnabled(el.leaveBtn, !state.leaveInFlight);
}

/* ---- cards ---- */

/** Only real card codes reach a URL; anything else shows a card back. */
export function cardImage(code) {
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

/* ---- opponents ---- */

function renderSeat(seatEl, player, game) {
    if (!player) {
        seatEl.innerHTML = "";
        delete seatEl.dataset.playerId;
        return;
    }
    seatEl.dataset.playerId = player.id;
    const fan = fanLayout(player.handSize);
    const backs = fan.angles.map((angle, i) =>
        `<span class="card-back-face card-back-face--fan" style="transform: rotate(${angle}deg); z-index: ${i + 1};" aria-hidden="true"></span>`
    ).join("");
    const fanClass = "back-fan" + (fan.few ? " back-fan--few" : "");
    const teamClass = game.players.length === 4 && Number.isInteger(player.team)
        ? ` seat-title--team${player.team}` : "";
    /* <wbr>: narrow seats may wrap between the name and its badges instead of mid-word. */
    /* The fan exposes only what it shows: an exact count below six, "6 or more" otherwise. */
    seatEl.innerHTML = `<div class="seat-title${teamClass}">${escapeHtml(player.name)}${botBadgeHtml(player)}<wbr>${roleTagsHtml(player, game)}<wbr></div>
        <div class="${fanClass}" role="img" aria-label="${escapeHtml(fanCountLabel(player.handSize))}">${backs}</div>`;
    syncSeatThinking(seatEl);
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
function syncSeatThinking(seatEl) {
    const title = seatEl.querySelector(".seat-title");
    if (!title) return;
    const message = seatEl.dataset.playerId ? state.botThinking[seatEl.dataset.playerId] : "";
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
export function updateBotThinkingIndicators() {
    for (const seatEl of [el.seatTop1, el.seatTop2, el.seatTop3]) {
        if (seatEl) syncSeatThinking(seatEl);
    }
}

/* ---- the table ---- */

function updateBattleTableBanner(game) {
    const banner = el.battleTableBanner;
    if (!banner) return;
    if (game.status !== "IN_PROGRESS" || !game.takingCardsInProgress) {
        banner.innerHTML = "";
        banner.classList.add("hidden");
        return;
    }
    const taking = game.players.find(p => p.id === game.takingPlayerId);
    const takingName = taking ? taking.name : "Defender";
    const limit = Number(game.takeLimit) || 0;
    const n = (game.table || []).length;
    const sub = n < limit
        ? "Throw in matching ranks (first come), then all attackers press End round."
        : "No more throw-ins. All attackers must press End round.";
    banner.innerHTML =
        `<span class="taking-lead-icon" aria-hidden="true">⇩</span><strong>${escapeHtml(takingName)}</strong> is taking cards. ${sub}`;
    banner.classList.remove("hidden");
}

function renderBattle(game) {
    const row = el.battleCards;
    row.innerHTML = "";
    if (!game.table || game.table.length === 0) {
        row.removeAttribute("role");
        row.removeAttribute("aria-label");
        row.innerHTML = "<div class='muted'>No cards on table</div>";
        return;
    }
    row.setAttribute("role", "list");
    row.setAttribute("aria-label", "Cards on the table");
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
        row.appendChild(pair);
    }
}

/* Drop-target highlighting while a card is dragged over the table. */
let battlePairDropHover = null;

export function setBattlePairDropHover(pair) {
    if (battlePairDropHover === pair) return;
    if (battlePairDropHover) battlePairDropHover.classList.remove("battle-pair-drop-target");
    battlePairDropHover = pair;
    if (pair) pair.classList.add("battle-pair-drop-target");
}

export function clearBattleDropUi() {
    el.battleCards.classList.remove("battle-drop-target");
    setBattlePairDropHover(null);
}

/* ---- the hand ---- */

function currentHandCodes() {
    return [...el.myHand.querySelectorAll(".hand-card-btn")].map(btn => btn.dataset.cardCode);
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
    if (isBusy() || state.seatInvalid || !state.game) return;
    state.selectedHandCard = state.selectedHandCard === code ? null : code;
    renderActionState(state.game);
    renderMyHand(state.game.players.find(p => p.id === seat.playerId));
}

/**
 * Keyed update of the hand: buttons are reused per card code and existing ones are never moved
 * (the sort order is stable), so the focused card keeps focus through every re-render.
 */
function renderMyHand(me) {
    const hand = sortCardCodesByRank(me?.hand || []);
    const busy = isBusy() || state.seatInvalid;
    const existing = new Map([...el.myHand.querySelectorAll(".hand-card-btn")].map(btn => [btn.dataset.cardCode, btn]));
    const wanted = new Set(hand);
    for (const [code, btn] of existing) {
        if (!wanted.has(code)) btn.remove();
    }
    for (const node of [...el.myHand.childNodes]) {
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
        const slot = previous ? previous.nextElementSibling : el.myHand.firstElementChild;
        if (btn !== slot) el.myHand.insertBefore(btn, slot);
        previous = btn;
    }
}

/** The action strip, the "Attack card to beat" menu and the hint line for the selected card. */
export function renderActionState(game) {
    const moves = game.legalMoves || {};
    const selected = state.selectedHandCard;
    const select = el.defendTargetSelect;
    const choice = defenceChoice(moves.defensesByAttackCard, selected, select.value);

    select.innerHTML = "";
    for (const attack of choice.options) {
        const option = document.createElement("option");
        option.value = attack;
        option.textContent = `vs ${cardName(attack)}`;
        select.appendChild(option);
    }
    if (choice.target) select.value = choice.target;
    select.classList.toggle("hidden", choice.options.length <= 1);

    // A rejected seat keeps its last snapshot on screen, but none of its moves can be made.
    const idle = !isBusy() && !state.seatInvalid;
    const allowed = actionAvailability(moves, selected, choice.canDefend);
    setControlEnabled(el.startBtn, idle && allowed.start);
    setControlEnabled(el.attackBtn, idle && allowed.attack);
    setControlEnabled(el.transferBtn, idle && allowed.transfer);
    setControlEnabled(el.defendBtn, idle && allowed.defend);
    setControlEnabled(el.takeBtn, idle && allowed.take);
    setControlEnabled(el.endRoundBtn, idle && allowed.endRound);

    el.actionHint.textContent = actionHint(game, seat.playerId, selected, choice.beatable);
}

/* ---- the whole page ---- */

const renderedHooks = [];

/** Runs `fn` after every render (main.js uses it to keep Open tables polling in step). */
export function onRendered(fn) {
    renderedHooks.push(fn);
}

/** Renders the current state, then puts keyboard focus somewhere meaningful (see settleFocus). */
export function render() {
    const focusBefore = describeFocus();
    const handBefore = currentHandCodes();
    renderView();
    for (const fn of renderedHooks) fn();
    // "Leave this game?" only makes sense while this seat's game is in progress.
    if (el.leaveDialog?.open && !leaveNeedsConfirmation()) el.leaveDialog.close();
    settleFocus(focusBefore, handBefore);
}

/** Leaving resets the table for everyone only while a game is in progress at a valid seat. */
export function leaveNeedsConfirmation() {
    return Boolean(seat.gameCode && state.game?.status === "IN_PROGRESS" && !state.seatInvalid);
}

/** What has focus, in terms that survive a re-render: a card code or a control id. */
function describeFocus() {
    const active = document.activeElement;
    if (!active || active === document.body) return null;
    if (active.classList.contains("hand-card-btn") && el.myHand.contains(active)) {
        return {kind: "card", code: active.dataset.cardCode};
    }
    return active.id ? {kind: "control", id: active.id} : null;
}

function canTakeFocus(target) {
    return Boolean(target && target.isConnected && !target.disabled && target.getClientRects().length > 0);
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
        hasSession: Boolean(seat.gameCode && seat.playerId && state.game),
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
    if (state.seatInvalid && canTakeFocus(el.seatNoticeLobbyBtn)) {
        // Nothing at the table can be used any more: offer the way out.
        el.seatNoticeLobbyBtn.focus();
        return;
    }

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
    let next = null;
    if (target.kind === "card") {
        next = [...el.myHand.querySelectorAll(".hand-card-btn")].find(btn => btn.dataset.cardCode === target.code);
    } else if (target.kind === "control") {
        next = document.getElementById(target.id);
    } else {
        next = document.getElementById(VIEW_HEADINGS[view] || "");
    }
    if (canTakeFocus(next)) next.focus();
}

function renderView() {
    const game = state.game;
    const hasSession = Boolean(seat.gameCode && seat.playerId && game);
    const reconnecting = Boolean(state.reconnecting && !hasSession);
    syncBusyControls();
    if (el.reconnectView) {
        el.reconnectView.classList.toggle("hidden", !reconnecting);
        if (el.reconnectCode) el.reconnectCode.textContent = reconnecting ? seat.gameCode : "";
    }
    el.lobbyView.classList.toggle("hidden", hasSession || reconnecting);
    el.gameView.classList.toggle("hidden", !hasSession);

    const showPlayingArea = hasSession && game && game.status === "IN_PROGRESS";
    const showResult = hasSession && game && game.status === "FINISHED";
    el.gameView.classList.toggle("game-view--active", Boolean(showPlayingArea || showResult));
    if (el.playingArea) el.playingArea.classList.toggle("hidden", !showPlayingArea);
    if (el.resultPanel) el.resultPanel.classList.toggle("hidden", !showResult);
    if (el.gameplayHint) el.gameplayHint.classList.toggle("hidden", !showPlayingArea || !state.showGameplayHelp);
    if (el.helpToggleBtn) {
        el.helpToggleBtn.classList.toggle("hidden", !showPlayingArea);
        el.helpToggleBtn.setAttribute("aria-expanded", String(showPlayingArea && state.showGameplayHelp));
        el.helpToggleBtn.textContent = showPlayingArea && state.showGameplayHelp ? "Hide help" : "Help ?";
    }
    if (el.roomWaitingLine) {
        el.roomWaitingLine.classList.toggle("hidden", !hasSession || !game || game.status !== "LOBBY");
        if (hasSession && game?.status === "LOBBY") {
            el.roomWaitingLine.textContent = game.publicRoom !== false
                ? "You’re in the room. Share the invite or let players find it under Open tables."
                : "This room is invite-only. Share the invite link or code with friends.";
        }
    }
    if (el.gameOpenTablesWrap) {
        el.gameOpenTablesWrap.classList.toggle(
            "hidden", !hasSession || !game || game.status !== "LOBBY" || game.publicRoom === false);
    }
    if (el.shareBtn) {
        el.shareBtn.classList.toggle("hidden", !hasSession || !game || game.status !== "LOBBY");
    }
    if (el.seatNotice) {
        const showNotice = Boolean(hasSession && state.seatInvalid);
        el.seatNotice.classList.toggle("hidden", !showNotice);
        const text = showNotice
            ? `This browser's seat in room ${seat.gameCode} is no longer valid. Go back to the lobby to join the room again or start a new game.`
            : "";
        if (el.seatNoticeText.textContent !== text) el.seatNoticeText.textContent = text;
    }

    if (!hasSession) return;

    const me = game.players.find(p => p.id === seat.playerId);
    const attacker = game.players.find(p => p.id === game.attackerPlayerId);
    const defender = game.players.find(p => p.id === game.defenderPlayerId);

    if (showResult && el.resultPanel) {
        const result = gameResult(game, seat.playerId);
        el.resultPanel.dataset.outcome = result?.outcome || "draw";
        el.resultIcon.textContent = result?.icon || "🃏";
        el.resultTitle.textContent = result?.title || "Game finished";
        el.resultSummary.textContent = result?.summary || "The game is complete.";
        const canRematch = game.hostPlayerId === seat.playerId;
        el.rematchBtn.classList.toggle("hidden", !canRematch);
        setControlEnabled(el.rematchBtn, !isBusy() && !state.seatInvalid);
        el.rematchWaiting.classList.toggle("hidden", canRematch);
    }

    el.gameCodeLabel.textContent = game.code;
    el.statusLabel.textContent = displayStatus(game.status);
    if (el.visibilityLabel) {
        const isPublic = game.publicRoom !== false;
        el.visibilityLabel.textContent = isPublic ? "Public room" : "Invite only";
        el.visibilityLabel.title = isPublic
            ? "This waiting room appears in Open tables."
            : "Only people with the room code can join.";
    }
    renderDeck(game);
    if (el.trumpSuitHud && game.trumpSuit) {
        const sym = trumpSuitGlyph(game.trumpSuit);
        const glyph = document.createElement("span");
        glyph.className = "pill-role";
        glyph.setAttribute("aria-hidden", "true");
        glyph.textContent = sym;
        const spoken = document.createElement("span");
        spoken.className = "visually-hidden";
        spoken.textContent = `Trump: ${suitName(game.trumpSuit) || sym}`;
        el.trumpSuitHud.replaceChildren(glyph, spoken);
        el.trumpSuitHud.title = `Trump ${sym}`;
        el.trumpSuitHud.classList.remove("hidden");
    } else if (el.trumpSuitHud) {
        el.trumpSuitHud.replaceChildren();
        el.trumpSuitHud.classList.add("hidden");
    }
    const playersList = game.players
        .map(p => p.id === game.hostPlayerId ? `${p.name} (host)` : p.name)
        .join(", ");
    el.roleLabel.textContent = `Players: ${playersList || "-"}`;
    const isHostLobby = Boolean(me && game.status === "LOBBY" && game.hostPlayerId === seat.playerId);
    if (el.startBtn) {
        el.startBtn.classList.toggle("hidden", !isHostLobby);
    }
    if (el.addBotBtn) {
        const hasBot = game.players.some(p => p.bot);
        const canAddBot = Boolean(isHostLobby && !hasBot && game.playerCount < game.maxPlayers);
        el.addBotBtn.classList.toggle("hidden", !isHostLobby || hasBot);
        setControlEnabled(el.addBotBtn, canAddBot && !isBusy() && !state.seatInvalid);
    }

    if (game.status === "FINISHED") {
        el.tableAttackerLabel.textContent = "-";
        el.tableDefenderLabel.textContent = "-";
    } else if (game.takingCardsInProgress) {
        el.tableAttackerLabel.textContent = attacker ? attacker.name : "-";
        if (defender) {
            el.tableDefenderLabel.innerHTML =
                `${escapeHtml(defender.name)}<span class="pill-take" title="Taking cards" aria-hidden="true">⇩</span>`
                + `<span class="visually-hidden">, taking the cards</span>`;
        } else {
            el.tableDefenderLabel.textContent = "-";
        }
    } else {
        el.tableAttackerLabel.textContent = attacker ? attacker.name : "-";
        el.tableDefenderLabel.textContent = defender ? defender.name : "-";
    }
    if (el.openingLeadHud) {
        const emptyTable = !game.table || game.table.length === 0;
        const showOpening =
            game.status === "IN_PROGRESS" && game.players.length === 4 && emptyTable && attacker;
        if (showOpening) {
            el.openingLeadHud.textContent =
                `${attacker.name} is attacking; teammates add matching ranks after.`;
            el.openingLeadHud.classList.remove("hidden");
        } else {
            el.openingLeadHud.textContent = "";
            el.openingLeadHud.classList.add("hidden");
        }
    }
    if (me) {
        let cls = "seat-title";
        if (game.players.length === 4 && me.team !== null && me.team !== undefined) {
            cls += ` seat-title--team${me.team}`;
        }
        el.mySeatTitle.className = cls;
        const showTags = game.status === "IN_PROGRESS" || game.status === "FINISHED";
        const myTagHtml = showTags && roleTags(me, game) ? ` ${roleTagsHtml(me, game)}` : "";
        el.mySeatTitle.innerHTML = `${escapeHtml(me.name)} (you)${botBadgeHtml(me)}${myTagHtml}`;
    } else {
        el.mySeatTitle.className = "seat-title";
        el.mySeatTitle.textContent = "You";
    }

    el.tableGrid.className = "table-grid players-" + game.players.length;
    const [top1, top2, top3] = seatRotation(game.players, seat.playerId);
    renderSeat(el.seatTop1, top1, game);
    renderSeat(el.seatTop2, top2, game);
    renderSeat(el.seatTop3, top3, game);

    renderBattle(game);
    updateBattleTableBanner(game);
    renderMyHand(me);
    renderActionState(game);
}

/** The draw pile with the trump card under it; hidden once the pile is empty. */
function renderDeck(game) {
    const talonCount = game.status === "IN_PROGRESS" && game.trumpCard
        ? Math.max(0, Number(game.talonSize) || 0)
        : 0;
    if (talonCount > 0) {
        el.deckArea.classList.remove("hidden");
        el.trumpUnderImg.src = cardImage(game.trumpCard);
        el.trumpUnderImg.alt = `Trump card: ${cardName(game.trumpCard)}`;
        el.trumpUnderImg.classList.remove("hidden");
        el.talonStack.innerHTML = "";
        const face = document.createElement("div");
        face.className = "card-back-face";
        face.setAttribute("aria-hidden", "true");
        const extra = Math.min(Math.max(talonCount - 1, 0), 10);
        if (extra > 0) {
            const y = Math.min(2 + extra * 0.35, 6);
            face.style.boxShadow = `0 ${y}px ${1 + extra * 0.15}px rgba(0,0,0,${0.12 + extra * 0.01})`;
        }
        el.talonStack.appendChild(face);
    } else {
        el.deckArea.classList.add("hidden");
        el.trumpUnderImg.classList.add("hidden");
        el.trumpUnderImg.removeAttribute("src");
        el.talonStack.innerHTML = "";
    }
}
