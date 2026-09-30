/*
 * Synthetic GameResponse payloads for UI tests that need table states the real server cannot
 * produce on demand (3-4 seated players, 20-card hands, a pending take, ...). Shapes mirror
 * controller/dto/GameResponse.java.
 */

const SUITS = ["C", "D", "H", "S"];
const RANKS = ["6", "7", "8", "9", "10", "J", "Q", "K", "A"];
export const DECK = RANKS.flatMap(rank => SUITS.map(suit => rank + suit));

export const SEAT_NAMES = ["Alexandra the Great", "Krzysztof Elektronik", "Bartholomew", "Konstantin"];

export function emptyLegalMoves() {
    return {
        canStart: false,
        canAttack: false,
        canDefend: false,
        canTransfer: false,
        canTake: false,
        canEndRound: false,
        attackableCardCodes: [],
        transferableCardCodes: [],
        defensesByAttackCard: {}
    };
}

/**
 * Builds an in-progress game seen by player "me" (seat 0). Opponents are "p2".."p4".
 * `hand` defaults to the first `handSize` cards of the deck that are not on the table.
 */
export function syntheticGame({
    code = "SYNTH2",
    version = 5,
    status = "IN_PROGRESS",
    players = 2,
    handSize = 6,
    hand = null,
    opponentHandSizes = [8, 3, 6],
    table = [],
    attackerPlayerId = "p2",
    defenderPlayerId = "me",
    takingCardsInProgress = false,
    takingPlayerId = null,
    trumpSuit = "S",
    trumpCard = "7S",
    talonSize = 10,
    botThinking = {},
    legalMoves = {},
    names = SEAT_NAMES,
    hostPlayerId = "me",
    loserPlayerId = null,
    publicRoom = true
} = {}) {
    const onTable = new Set(table.flatMap(pair => [pair.attackCard, pair.defenseCard].filter(Boolean)));
    const myHand = hand ?? DECK.filter(card => !onTable.has(card) && card !== trumpCard).slice(0, handSize);
    const ids = ["me", "p2", "p3", "p4"].slice(0, players);
    const seats = ids.map((id, index) => ({
        id,
        name: names[index],
        bot: index === 1,
        joinedAt: "2026-08-28T12:00:00Z",
        team: players === 4 ? index % 2 : null,
        handSize: index === 0 ? myHand.length : opponentHandSizes[index - 1],
        hand: index === 0 ? myHand : []
    }));
    const loser = seats.find(seat => seat.id === loserPlayerId) || null;
    // Like the server: a host waiting with two or more players may start.
    const canStart = status === "LOBBY" && hostPlayerId === "me" && seats.length >= 2;
    return {
        code,
        status,
        version,
        maxPlayers: 4,
        playerCount: seats.length,
        createdAt: "2026-08-28T12:00:00Z",
        lastActivityAt: "2026-08-28T12:10:00Z",
        publicRoom,
        hostPlayerId,
        attackerPlayerId: status === "IN_PROGRESS" ? attackerPlayerId : null,
        defenderPlayerId: status === "IN_PROGRESS" ? defenderPlayerId : null,
        takingCardsInProgress,
        takingPlayerId,
        takeLimit: takingCardsInProgress ? 6 : 0,
        loserPlayerId,
        loserPlayerName: loser ? loser.name : null,
        loserTeam: loser ? loser.team : null,
        trumpSuit: status === "IN_PROGRESS" ? trumpSuit : null,
        trumpCard: status === "IN_PROGRESS" ? trumpCard : null,
        talonSize: status === "IN_PROGRESS" ? talonSize : 0,
        table: table.map(pair => ({attackerId: attackerPlayerId, defenseCard: null, ...pair})),
        players: seats,
        botThinking,
        legalMoves: {...emptyLegalMoves(), canStart, ...legalMoves}
    };
}

/** Seeds this tab's saved seat before any page script runs. */
export async function seedSession(page, {code = "SYNTH2", playerId = "me", token = "me-secret"} = {}) {
    await page.addInitScript(([gameCode, id, secret]) => {
        sessionStorage.setItem("durak_game_code", gameCode);
        sessionStorage.setItem("durak_player_id", id);
        sessionStorage.setItem("durak_player_token", secret);
    }, [code, playerId, token]);
}

/**
 * Serves `game` (an object, or a function returning one) for every request under
 * /api/games/<code>, and keeps the realtime socket open but silent. Returns the websocket
 * route handle getter so tests can push invalidations.
 */
export async function serveGame(page, game, {code = "SYNTH2"} = {}) {
    const sockets = [];
    await page.routeWebSocket("**/ws/games/**", socket => { sockets.push(socket); });
    await page.route(`**/api/games/${code}**`, route => route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(typeof game === "function" ? game(route.request()) : game)
    }));
    return {sockets};
}

export async function horizontalOverflow(page) {
    return page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth
    }));
}
