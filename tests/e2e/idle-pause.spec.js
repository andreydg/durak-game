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

/** Serves `current()` as the seat's game, and counts its sockets, reads and heartbeats. */
async function watchGame(page, current, {onRematch = () => {}} = {}) {
    const game = {opened: 0, closed: 0, reads: 0, heartbeats: 0, sockets: []};
    await seedSession(page);
    await page.routeWebSocket("**/ws/lobbies", () => {});
    await page.route("**/api/lobbies", route => route.fulfill({json: []}));
    await page.routeWebSocket("**/ws/games/**", socket => {
        game.opened++;
        game.sockets.push(socket);
        socket.onClose(() => { game.closed++; });
    });
    await page.route("**/api/games/SYNTH2**", route => {
        const request = route.request();
        const path = new URL(request.url()).pathname;
        if (request.method() === "GET") game.reads++;
        else if (path.endsWith("/heartbeat")) game.heartbeats++;
        else if (path.endsWith("/rematch")) onRematch();
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
        for (const type of ["pointerdown", "pointermove", "keydown", "wheel", "touchstart", "click"]) {
            window.addEventListener(type, note, {capture: true, passive: true});
        }
        window.addEventListener("focus", note);
    });
}
const clearInput = page => page.evaluate(() => { window.inputSeen = []; });
async function expectNoInput(page) {
    expect(await page.evaluate(() => window.inputSeen), "no input reached the page while it idled").toEqual([]);
}

/*
 * The sockets the page opens and closes and the requests it sends, recorded page-side as they
 * happen. The routes' counts arrive over the network, so only this record is settled when a clock
 * call returns, and only it can show that something did not happen. Installed once the page has
 * loaded, over the WebSocket class Playwright routes sockets through.
 */
async function recordTraffic(page) {
    await page.evaluate(() => {
        const traffic = window.trafficSeen = [];
        const path = url => new URL(String(url), window.location.href).pathname;
        const RoutedSocket = window.WebSocket;
        const close = RoutedSocket.prototype.close;
        RoutedSocket.prototype.close = function (...args) {
            traffic.push(`close ${path(this.url)}`);
            return close.apply(this, args);
        };
        window.WebSocket = class extends RoutedSocket {
            constructor(...args) {
                super(...args);
                traffic.push(`open ${path(this.url)}`);
            }
        };
        const fetch = window.fetch;
        window.fetch = (input, init = {}) => {
            traffic.push(`${init.method || "GET"} ${path(input)}`);
            return fetch(input, init);
        };
    });
}
/** The traffic recorded since the last call. */
const takeTraffic = page => page.evaluate(() => window.trafficSeen.splice(0));
const socketEvents = traffic => traffic.filter(entry => /^(open|close) /.test(entry));

/*
 * Moves only the wall clock (Date.now()), as a system clock correction does. performance.now() and
 * timers keep their pace, so this also stands for a sleep the monotonic clock doesn't count.
 */
async function moveWallClock(page, ms) {
    const now = await page.evaluate(() => Date.now());
    await page.clock.setSystemTime(now + ms);
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

    test("a system clock set back doesn't hold the pause off, nor does input after it", async ({page}) => {
        const lobby = await watchOpenTables(page);
        await page.goto("/");
        await expect.poll(() => lobby.opened).toBe(1);
        await recordTraffic(page);
        await clearInput(page);

        // The clock is corrected an hour back after the last input: idle 15 minutes later all the same.
        await moveWallClock(page, -60 * 60 * 1000);
        await page.clock.fastForward("15:01");
        await expectNoInput(page);
        expect(socketEvents(await takeTraffic(page))).toEqual(["close /ws/lobbies"]);

        // Input afterwards counts from then too: idle again after 15 minutes, not an hour.
        await page.keyboard.press("Shift");
        expect(socketEvents(await takeTraffic(page))).toEqual(["open /ws/lobbies"]);
        await clearInput(page);
        await page.clock.fastForward("14:00");
        expect(socketEvents(await takeTraffic(page))).toEqual([]);
        await page.clock.fastForward("01:01");
        await expectNoInput(page);
        expect(socketEvents(await takeTraffic(page))).toEqual(["close /ws/lobbies"]);
        await expect.poll(() => lobby.closed).toBe(2);
    });

    test("a clock set forward, or a sleep the monotonic clock leaves out, doesn't bring the pause early", async ({page}) => {
        const lobby = await watchOpenTables(page);
        await page.goto("/");
        await expect.poll(() => lobby.opened).toBe(1);
        await recordTraffic(page);

        await page.clock.fastForward("10:00");
        await page.keyboard.press("Shift");
        await clearInput(page);
        await moveWallClock(page, 2 * 60 * 60 * 1000);
        await page.clock.fastForward("14:00");
        expect(socketEvents(await takeTraffic(page))).toEqual([]);
        await page.clock.fastForward("01:01");
        await expectNoInput(page);
        expect(socketEvents(await takeTraffic(page))).toEqual(["close /ws/lobbies"]);
        await expect.poll(() => lobby.closed).toBe(1);
    });

    test("a sleep the monotonic clock counts wakes up paused, and the next input resumes", async ({page}) => {
        const lobby = await watchOpenTables(page);
        await page.goto("/");
        await expect.poll(() => lobby.opened).toBe(1);
        await recordTraffic(page);
        await clearInput(page);

        // Two hours asleep: every timer is overdue on waking and fires once.
        await page.clock.fastForward("02:00:00");
        await expectNoInput(page);
        expect(socketEvents(await takeTraffic(page))).toEqual(["close /ws/lobbies"]);

        await page.mouse.move(40, 40);
        expect(socketEvents(await takeTraffic(page))).toEqual(["open /ws/lobbies"]);
        await expect.poll(() => lobby.opened).toBe(2);
    });

    test("a click alone on Play again after idling brings back the game's socket and heartbeat, once each", async ({page}) => {
        let current = finishedGame();
        const game = await watchGame(page, () => current, {
            onRematch: () => { current = syntheticGame({version: 8}); }
        });
        await page.goto("/");
        const playAgain = page.locator("#rematchBtn");
        await expect(playAgain).toBeVisible();
        await expect.poll(() => game.opened).toBe(1);
        await recordTraffic(page);
        await clearInput(page);

        await page.clock.fastForward("15:30");
        await expectNoInput(page);
        expect(socketEvents(await takeTraffic(page))).toEqual(["close /ws/games/SYNTH2"]);

        // Focus the app moves itself is still not use.
        await playAgain.evaluate(button => button.focus());
        expect(await takeTraffic(page)).toEqual([]);

        // Screen readers and voice control activate a button with a click and nothing before it.
        await playAgain.evaluate(button => button.click());
        const inputs = await page.evaluate(() => window.inputSeen);
        expect(inputs).toHaveLength(1);
        expect(inputs[0]).toMatch(/^click trusted=false /);
        const onClick = await takeTraffic(page);
        expect(socketEvents(onClick)).toEqual(["open /ws/games/SYNTH2"]);
        expect(onClick).toContain("POST /api/games/SYNTH2/rematch");
        await expect(playAgain).toBeHidden();
        await expect.poll(() => game.opened).toBe(2);
        await clearInput(page);

        // One heartbeat every five minutes and still one socket: nothing was started twice.
        for (let i = 0; i < 2; i++) {
            await page.clock.fastForward("05:00");
            const traffic = await takeTraffic(page);
            expect(traffic.filter(entry => entry.endsWith("/heartbeat"))).toEqual(["POST /api/games/SYNTH2/heartbeat"]);
            expect(socketEvents(traffic)).toEqual([]);
        }
        await expect.poll(() => game.heartbeats).toBe(2);

        // No longer idle: a game that finishes now keeps its updates until 15 minutes after the click.
        current = finishedGame(9);
        game.sockets.at(-1).send(JSON.stringify({type: "GAME_UPDATED", version: 9}));
        await expect(playAgain).toBeVisible();
        expect(socketEvents(await takeTraffic(page))).toEqual([]);
        await page.clock.fastForward("05:30");
        await expectNoInput(page);
        expect(socketEvents(await takeTraffic(page))).toEqual(["close /ws/games/SYNTH2"]);
        expect(game.opened).toBe(2);
    });
});
