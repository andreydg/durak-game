import { syntheticGame } from "./synthetic.js";

export const CODE = "SYNTH2";

/*
 * The bot led 6D and hearts are trump, so 6H can either transfer (same rank, nothing beaten yet)
 * or beat the 6D. Dropping it on the 6D defends; dropping it on the open felt transfers.
 */
export function sixesGame(overrides = {}) {
    return syntheticGame({
        version: 7,
        attackerPlayerId: "p2",
        defenderPlayerId: "me",
        trumpSuit: "H",
        trumpCard: "9H",
        hand: ["6H", "7H", "JH", "QC", "KD"],
        table: [{attackCard: "6D", attackerId: "p2"}],
        legalMoves: {
            canDefend: true,
            canTransfer: true,
            canTake: true,
            transferableCardCodes: ["6H"],
            defensesByAttackCard: {"6D": ["6H", "7H", "JH", "KD"]}
        },
        ...overrides
    });
}

/**
 * Serves `current()` and records the table moves the page posts. `hold(action)` may return a
 * promise to keep that move's response pending (for example to test a request in flight).
 * `sockets` collects the game's realtime sockets, to push a GAME_UPDATED that makes it read again.
 */
export async function routeMoves(page, current = sixesGame, {hold = () => null, sockets = []} = {}) {
    const moves = [];
    await page.routeWebSocket("**/ws/games/**", socket => { sockets.push(socket); });
    await page.route(`**/api/games/${CODE}**`, async route => {
        const request = route.request();
        const action = new URL(request.url()).pathname.slice(`/api/games/${CODE}`.length);
        if (request.method() === "POST" && ["/attack", "/defend", "/transfer"].includes(action)) {
            moves.push({action, body: request.postDataJSON()});
            await hold(action);
        }
        await route.fulfill({status: 200, contentType: "application/json", body: JSON.stringify(current())});
    });
    return moves;
}

export async function centre(locator) {
    const box = await locator.boundingBox();
    return {x: box.x + box.width / 2, y: box.y + box.height / 2};
}
