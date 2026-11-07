import { test, expect } from "@playwright/test";
import { seedSession, syntheticGame } from "./support/synthetic.js";

/*
 * A page nobody is using keeps no socket open on the server: after 15 minutes without input, Open
 * tables (and a finished game's updates) pause, as in a hidden tab, until the next interaction.
 * Without this, one forgotten tab kept a Cloud Run instance busy around the clock: the client
 * reconnected each time the server's request timeout ended the socket. Playwright's clock stands
 * in for the wait.
 */

/** Serves Open tables and counts its sockets (opened and closed by the page) and list reads. */
async function watchOpenTables(page) {
    const lobby = {opened: 0, closed: 0, reads: 0};
    await page.route("**/api/lobbies", route => {
        lobby.reads++;
        return route.fulfill({json: []});
    });
    await page.routeWebSocket("**/ws/lobbies", socket => {
        lobby.opened++;
        socket.onClose(() => { lobby.closed++; });
        socket.send(JSON.stringify({type: "LOBBIES_READY", streamId: "idle-pause", revision: lobby.opened}));
    });
    return lobby;
}

/** Serves `current()` as the seat's game, and counts its sockets and reads. */
async function watchGame(page, current) {
    const game = {opened: 0, closed: 0, reads: 0, sockets: []};
    await seedSession(page);
    await page.routeWebSocket("**/ws/lobbies", () => {});
    await page.route("**/api/lobbies", route => route.fulfill({json: []}));
    await page.routeWebSocket("**/ws/games/**", socket => {
        game.opened++;
        game.sockets.push(socket);
        socket.onClose(() => { game.closed++; });
    });
    await page.route("**/api/games/SYNTH2**", route => {
        if (route.request().method() === "GET") game.reads++;
        return route.fulfill({json: current()});
    });
    return game;
}

const finishedGame = (version = 7) => syntheticGame({status: "FINISHED", loserPlayerId: "p2", hand: [], version});

/*
 * Input the page receives counts as use and starts the 15 minutes again. A test expecting a pause
 * first checks that none arrived since it last cleared the log (inputSeen), so a stray event (a
 * window focus, say) is named in the failure instead of surfacing as a socket that never closed.
 */
async function recordInput(page) {
    await page.addInitScript(() => {
        window.inputSeen = [];
        const note = event => window.inputSeen.push(`${event.type} trusted=${event.isTrusted} at ${new Date().toISOString()}`);
        for (const type of ["pointerdown", "pointermove", "keydown", "wheel", "touchstart"]) {
            window.addEventListener(type, note, {capture: true, passive: true});
        }
        window.addEventListener("focus", note);
    });
}
const clearInput = page => page.evaluate(() => { window.inputSeen = []; });
async function expectNoInput(page) {
    expect(await page.evaluate(() => window.inputSeen), "no input reached the page while it idled").toEqual([]);
}

test.describe("An idle page lets go of the server", () => {
    test.beforeEach(async ({page}) => {
        await page.clock.install();
        await recordInput(page);
    });

    test("the home page closes Open tables after 15 idle minutes and reopens it on the next input", async ({page}) => {
        const lobby = await watchOpenTables(page);
        await page.goto("/");
        await expect.poll(() => lobby.opened).toBe(1);
        await expect.poll(() => lobby.reads).toBeGreaterThan(0);
        await clearInput(page);

        await page.clock.fastForward("14:00");
        expect(lobby.closed).toBe(0);
        await page.clock.fastForward("01:30");
        await expectNoInput(page);
        await expect.poll(() => lobby.closed).toBe(1);

        // However long the tab stays open, nothing reconnects or polls while nobody is there.
        const reads = lobby.reads;
        await page.clock.fastForward("01:00:00");
        await page.clock.fastForward("01:00:00");
        expect(lobby.opened).toBe(1);
        expect(lobby.reads).toBe(reads);

        // The next input brings the list back at once, freshly read.
        await page.mouse.move(40, 40);
        await expect.poll(() => lobby.opened).toBe(2);
        await expect.poll(() => lobby.reads).toBeGreaterThan(reads);
        expect(lobby.closed).toBe(1);
    });

    test("any input starts the 15 minutes again", async ({page}) => {
        const lobby = await watchOpenTables(page);
        await page.goto("/");
        await expect.poll(() => lobby.opened).toBe(1);

        await page.clock.fastForward("10:00");
        await page.keyboard.press("Shift");
        await clearInput(page);
        await page.clock.fastForward("10:00");
        expect(lobby.closed).toBe(0);
        await page.clock.fastForward("05:30");
        await expectNoInput(page);
        await expect.poll(() => lobby.closed).toBe(1);
    });

    test("a finished game left open stops its updates after 15 idle minutes, and catches up on the next input", async ({page}) => {
        const game = await watchGame(page, () => finishedGame());
        await page.goto("/");
        await expect(page.locator("#gameView")).toBeVisible();
        await expect.poll(() => game.opened).toBe(1);
        await clearInput(page);

        await page.clock.fastForward("15:30");
        await expectNoInput(page);
        await expect.poll(() => game.closed).toBe(1);
        const reads = game.reads;
        await page.clock.fastForward("01:00:00");
        expect(game.opened).toBe(1);
        expect(game.reads).toBe(reads);

        await page.keyboard.press("Shift");
        await expect.poll(() => game.opened).toBe(2);
        await expect.poll(() => game.reads).toBeGreaterThan(reads);
    });

    test("a game in progress keeps its updates while its player is idle", async ({page}) => {
        const game = await watchGame(page, () => syntheticGame());
        await page.goto("/");
        await expect(page.locator("#gameView")).toBeVisible();
        await expect.poll(() => game.opened).toBe(1);

        await page.clock.fastForward("16:00");
        await page.clock.fastForward("30:00");
        expect(game.closed).toBe(0);
        expect(game.opened).toBe(1);
    });

    test("a game that ends while its player is idle stops its updates once the result arrives", async ({page}) => {
        let finished = false;
        const game = await watchGame(page, () => (finished ? finishedGame(99) : syntheticGame()));
        await page.goto("/");
        await expect(page.locator("#gameView")).toBeVisible();
        await expect.poll(() => game.opened).toBe(1);
        await clearInput(page);

        await page.clock.fastForward("16:00");
        expect(game.closed).toBe(0);
        await expectNoInput(page);
        finished = true;
        game.sockets.at(-1).send(JSON.stringify({type: "GAME_UPDATED", version: 99}));
        await expect.poll(() => game.closed).toBe(1);
        const reads = game.reads;
        await page.clock.fastForward("01:00:00");
        expect(game.opened).toBe(1);
        expect(game.reads).toBe(reads);
    });
});
