import {test, expect} from "@playwright/test";
import {setDefaultFont} from "./support/editorial-layout.js";
import {seedSession, serveGame, syntheticGame} from "./support/synthetic.js";

const LONG_NAMES = ["Alexandra Konstantinovna", "Maximilian Wojciechowski",
    "Christopher Montgomery", "Konstantin Alexandrovich"];

async function assertHandClearsHeader(page, knownLinuxSpaceLimit = false) {
    await page.evaluate(() => document.fonts.ready);
    // Let layout and its ResizeObserver settle before exercising native focus scrolling.
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await page.evaluate(() => {
        window.scrollTo(0, document.documentElement.scrollHeight);
        document.querySelector("#myHand .hand-card-btn:last-child").focus();
    });
    const focusedCards = [];
    for (let i = 0; i < 6; i++) {
        if (i) await page.keyboard.press("Shift+Tab");
        const metrics = await page.evaluate(() => ({
            isCard: document.activeElement.matches(".hand-card-btn"),
            card: document.activeElement.getBoundingClientRect().top,
            bottom: document.activeElement.getBoundingClientRect().bottom,
            height: document.activeElement.getBoundingClientRect().height,
            margin: parseFloat(getComputedStyle(document.activeElement).scrollMarginTop),
            header: document.querySelector(".room-header").getBoundingClientRect().bottom,
            viewport: window.innerHeight
        }));
        expect(metrics.isCard).toBe(true);
        expect(metrics.bottom).toBeLessThanOrEqual(metrics.viewport);
        focusedCards.push(metrics);
    }
    if (knownLinuxSpaceLimit) {
        // Check the exact known geometry and working observer BEFORE expecting failure, so
        // broken focus, bottom overflow, a missing observer or a changed design still fail.
        for (const metrics of focusedCards) {
            expect(metrics.header).toBeCloseTo(293.5, 0);
            expect(metrics.height).toBeCloseTo(79.40625, 0);
            expect(metrics.header + metrics.height).toBeGreaterThan(metrics.viewport);
            expect(metrics.margin).toBeCloseTo(metrics.header + 4, 1);
            expect(metrics.header - metrics.card).toBeLessThanOrEqual(14);
        }
        test.fail(true, "Known Linux 701×360 / 20px four-player limit: 66.5px below the header cannot fit a 79.4px card; design preserved (PR #50).");
    }
    // Keep the original zero-overlap assertion, including in the one expected-failure case.
    for (const metrics of focusedCards) {
        expect(metrics.card).toBeGreaterThanOrEqual(metrics.header);
    }
}

async function openGame(page, options, width, height = 900) {
    await page.setViewportSize({width, height});
    const game = syntheticGame(options);
    // Include every host action when measuring the waiting-room fallback.
    if (game.status === "LOBBY") game.players[1].bot = false;
    await seedSession(page);
    await serveGame(page, game);
    await page.route("**/api/lobbies", route => route.fulfill({json: []}));
    await page.routeWebSocket("**/ws/lobbies", () => {});
    await page.goto("/");
    await expect(page.locator("#gameView")).toBeVisible();
    await page.evaluate(() => document.fonts.ready);
}

test.describe("Header focus does not scroll the table", () => {
    for (const [width, height] of [[844, 390], [1024, 600], [1024, 768], [1280, 720], [1366, 768]]) {
        test(`Shift+Tab from the hand into the header at ${width}x${height}`, async ({page}) => {
            await openGame(page, {table: [{attackCard: "9H"}]}, width, height);
            await page.evaluate(() => {
                window.scrollTo(0, document.documentElement.scrollHeight);
                document.querySelector("#myHand .hand-card-btn").focus();
            });
            const before = await page.evaluate(() => window.scrollY);
            expect(before).toBeGreaterThan(0);
            // Do not use locator.click/tap: their scroll-into-view would fake this regression.
            for (let i = 0; i < 12; i++) {
                await page.keyboard.press("Shift+Tab");
                if (await page.evaluate(() => Boolean(document.activeElement.closest(".room-header")))) break;
            }
            await expect(page.locator("#leaveBtn")).toBeFocused();
            expect(await page.evaluate(() => window.scrollY)).toBe(before);
        });
    }

    for (const id of ["leaveBtn", "roomHeading", "copyCodeBtn"]) {
        test(`programmatic focus on ${id} keeps the table in place`, async ({page}) => {
            await openGame(page, {table: [{attackCard: "9H"}]}, 1280, 720);
            const before = await page.evaluate(() => {
                window.scrollTo(0, document.documentElement.scrollHeight);
                return window.scrollY;
            });
            expect(before).toBeGreaterThan(0);
            await page.evaluate(id => document.getElementById(id).focus(), id);
            await expect(page.locator(`#${id}`)).toBeFocused();
            expect(await page.evaluate(() => window.scrollY)).toBe(before);
        });
    }

    for (const width of [701, 768, 820, 834]) {
        for (const height of [360, 390]) {
            test(`four-player hand clears the wrapped header at ${width}x${height}`, async ({page}) => {
                // Tall viewports already expose the whole hand, even without a scroll margin.
                // Landscape heights force native focus scrolling past the wrapped roster.
                await openGame(page, {players: 4, table: [{attackCard: "9H"}]}, width, height);
                await page.evaluate(() => {
                    window.scrollTo(0, document.documentElement.scrollHeight);
                    document.querySelector("#myHand .hand-card-btn:last-child").focus();
                });
                for (let i = 0; i < 6; i++) {
                    if (i) await page.keyboard.press("Shift+Tab");
                    const metrics = await page.evaluate(() => ({
                        isCard: document.activeElement.matches(".hand-card-btn"),
                        card: document.activeElement.getBoundingClientRect().top,
                        bottom: document.activeElement.getBoundingClientRect().bottom,
                        header: document.querySelector(".room-header").getBoundingClientRect().bottom,
                        viewport: window.innerHeight
                    }));
                    expect(metrics.isCard).toBe(true);
                    expect(metrics.card).toBeGreaterThanOrEqual(metrics.header);
                    expect(metrics.bottom).toBeLessThanOrEqual(metrics.viewport);
                }
            });
        }
    }

    test("programmatic focus on How to play keeps the table in place", async ({page}) => {
        await openGame(page, {table: [{attackCard: "9H"}]}, 1280, 720);
        const before = await page.evaluate(() => {
            window.scrollTo(0, document.documentElement.scrollHeight);
            return window.scrollY;
        });
        expect(before).toBeGreaterThan(0);
        await page.evaluate(() => document.querySelector("#gameView .game-rules-link").focus());
        await expect(page.locator("#gameView .game-rules-link")).toBeFocused();
        expect(await page.evaluate(() => window.scrollY)).toBe(before);
    });
});

test.describe("Card focus follows the measured header", () => {
    for (const [width, height] of [[701, 360], [701, 390], [740, 360], [740, 390],
        [768, 360], [768, 390], [834, 390]]) {
        test(`long four-player names clear at ${width}x${height}`, async ({page}) => {
            await openGame(page, {players: 4, names: LONG_NAMES, table: [{attackCard: "9H"}]}, width, height);
            await assertHandClearsHeader(page);
        });
    }

    for (const players of [2, 4]) {
        for (const height of [360, 390]) {
            test(`20px text with ${players} players clears at 701x${height}`, async ({page, browserName, isMobile}) => {
                await setDefaultFont(page, 20);
                await openGame(page, {players, table: [{attackCard: "9H"}]}, 701, height);
                const knownLinuxSpaceLimit = process.platform === "linux" && browserName === "chromium"
                    && !isMobile && players === 4 && height === 360;
                await assertHandClearsHeader(page, knownLinuxSpaceLimit);
            });
        }
    }

    test("clearance follows a taller roster received during play", async ({page}) => {
        await page.setViewportSize({width: 701, height: 390});
        let game = syntheticGame({players: 4, table: [{attackCard: "9H"}]});
        await seedSession(page);
        const {sockets} = await serveGame(page, () => game);
        await page.route("**/api/lobbies", route => route.fulfill({json: []}));
        await page.routeWebSocket("**/ws/lobbies", () => {});
        await page.goto("/");
        await expect(page.locator("#gameView")).toBeVisible();
        await assertHandClearsHeader(page);
        const before = await page.locator(".room-header").evaluate(node => node.getBoundingClientRect().height);
        await expect.poll(() => sockets.length).toBeGreaterThan(0);
        game = syntheticGame({players: 4, names: LONG_NAMES, version: game.version + 1, table: [{attackCard: "9H"}]});
        sockets.at(-1).send(JSON.stringify({type: "GAME_UPDATED", version: game.version}));
        await expect(page.locator("#roleLabel")).toContainText(LONG_NAMES[3]);
        expect(await page.locator(".room-header").evaluate(node => node.getBoundingClientRect().height)).toBeGreaterThan(before);
        await assertHandClearsHeader(page);
    });
});

const fallbackCases = [
    ["host waiting", {status: "LOBBY"}, 20, 768],
    ["host waiting", {status: "LOBBY"}, 20, 770],
    ["two-player", {players: 2}, 24, 320],
    ["four-player", {players: 4}, 24, 320],
    ["finished", {status: "FINISHED", loserPlayerId: "p2", hand: []}, 24, 320],
    ["host waiting", {status: "LOBBY"}, 24, 768],
    ["host waiting", {status: "LOBBY"}, 24, 770],
    ["host waiting", {status: "LOBBY"}, 24, 820],
    ["guest waiting", {status: "LOBBY", hostPlayerId: "p2"}, 24, 700],
    ["guest waiting", {status: "LOBBY", hostPlayerId: "p2"}, 24, 710]
];

for (const touch of [false, true]) {
    test.describe(`Fallback copy button with ${touch ? "touch" : "mouse"}`, () => {
        test.use({hasTouch: touch, isMobile: touch});
        for (const [name, options, font, width] of fallbackCases) {
            test(`${name}, ${font}px text at ${width}px keeps controls separate`, async ({page}) => {
                await setDefaultFont(page, font);
                await page.route("**/css/game-editorial.css*", route => route.fulfill({status: 404, body: ""}));
                await openGame(page, options, width);
                const metrics = await page.evaluate(() => {
                    const copy = document.querySelector("#copyCodeBtn");
                    const box = copy.getBoundingClientRect();
                    const controls = [...document.querySelectorAll(".room-actions > *")]
                        .filter(node => node.getClientRects().length > 0);
                    const hitsCentre = node => {
                        const r = node.getBoundingClientRect();
                        return node.contains(document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2));
                    };
                    return {
                        overlaps: controls.map(node => {
                            const r = node.getBoundingClientRect();
                            return {label: node.textContent.trim(), area:
                                Math.max(0, Math.min(box.right, r.right) - Math.max(box.left, r.left)) *
                                Math.max(0, Math.min(box.bottom, r.bottom) - Math.max(box.top, r.top))};
                        }),
                        hits: [copy, ...controls].map(node => ({label: node.id || node.textContent.trim(), hit: hitsCentre(node)})),
                        coarse: matchMedia("(pointer: coarse)").matches
                    };
                });
                expect(metrics.coarse).toBe(touch);
                // Text-range metrics cannot see an icon-only button. Check its actual box.
                for (const {label, area} of metrics.overlaps) expect(area, label).toBe(0);
                if (touch) for (const {label, hit} of metrics.hits) expect(hit, label).toBe(true);
            });
        }
    });
}
