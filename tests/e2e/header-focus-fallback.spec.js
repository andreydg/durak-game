import {test, expect} from "@playwright/test";
import {setDefaultFont} from "./support/editorial-layout.js";
import {seedSession, serveGame, syntheticGame} from "./support/synthetic.js";

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
        test(`four-player hand clears the wrapped header at ${width}px`, async ({page}) => {
            await openGame(page, {players: 4, table: [{attackCard: "9H"}]}, width, 600);
            await page.evaluate(() => {
                window.scrollTo(0, document.documentElement.scrollHeight);
                document.querySelector("#myHand .hand-card-btn:last-child").focus();
            });
            for (let i = 0; i < 6; i++) {
                if (i) await page.keyboard.press("Shift+Tab");
                const metrics = await page.evaluate(() => ({
                    isCard: document.activeElement.matches(".hand-card-btn"),
                    card: document.activeElement.getBoundingClientRect().top,
                    header: document.querySelector(".room-header").getBoundingClientRect().bottom
                }));
                expect(metrics.isCard).toBe(true);
                expect(metrics.card).toBeGreaterThanOrEqual(metrics.header);
            }
        });
    }
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
