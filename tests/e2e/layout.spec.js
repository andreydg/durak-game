import { test, expect } from "@playwright/test";
import { horizontalOverflow, seedSession, serveGame, syntheticGame } from "./support/synthetic.js";

const busyTable = [
    {attackCard: "9H", defenseCard: "JH"},
    {attackCard: "9C"},
    {attackCard: "10H", defenseCard: "QH"}
];

async function openTable(page, {width, players, handSize = 6}) {
    await page.setViewportSize({width, height: 844});
    await seedSession(page);
    await serveGame(page, syntheticGame({
        players,
        handSize,
        table: busyTable,
        botThinking: {p2: "planning throw-in..."},
        legalMoves: {canDefend: true, canTake: true}
    }));
    await page.goto("/");
    await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(handSize);
}

function overlaps(a, b) {
    return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
}

test.describe("Game table layout", () => {
    for (const width of [320, 375, 390]) {
        for (const players of [2, 3, 4]) {
            test(`${players}-player table fits a ${width}px screen`, async ({ page }) => {
                await openTable(page, {width, players});

                const {scrollWidth, clientWidth} = await horizontalOverflow(page);
                expect(scrollWidth).toBeLessThanOrEqual(clientWidth);

                // A normal six-card hand fits without scrolling, and every action stays on screen.
                const rail = page.locator(".hand-scroll-x");
                expect(await rail.evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true);
                const lastCard = await page.locator("#myHand .hand-card-btn").last().boundingBox();
                const railBox = await rail.boundingBox();
                expect(lastCard.x + lastCard.width).toBeLessThanOrEqual(railBox.x + railBox.width + 0.5);
                const actionTops = new Set();
                for (const id of ["#attackBtn", "#defendBtn", "#transferBtn", "#takeBtn", "#endRoundBtn"]) {
                    const box = await page.locator(id).boundingBox();
                    expect(box.x + box.width, id).toBeLessThanOrEqual(clientWidth);
                    actionTops.add(Math.round(box.y));
                }
                // The sticky action strip is a single compact row, so it hides as little as possible.
                expect(actionTops.size).toBe(1);

                // The draw pile and trump never cover a player's name.
                const titles = [await page.locator("#mySeatTitle").boundingBox()];
                for (const title of await page.locator(".seat.top .seat-title").all()) {
                    titles.push(await title.boundingBox());
                }
                for (const piece of ["#trumpUnderImg", "#talonStack .card-back-face"]) {
                    const box = await page.locator(piece).boundingBox();
                    expect(box, piece).not.toBeNull();
                    for (const title of titles.filter(Boolean)) {
                        expect(overlaps(box, title), `${piece} overlaps a seat title`).toBe(false);
                    }
                }
            });
        }
    }

    for (const width of [375, 1280]) {
        test(`an 18-card hand scrolls inside its rail at ${width}px`, async ({ page }) => {
            await openTable(page, {width, players: 2, handSize: 18});

            const {scrollWidth, clientWidth} = await horizontalOverflow(page);
            expect(scrollWidth).toBeLessThanOrEqual(clientWidth);

            const rail = page.locator(".hand-scroll-x");
            const metrics = await rail.evaluate(el => ({scrollWidth: el.scrollWidth, clientWidth: el.clientWidth}));
            expect(metrics.scrollWidth).toBeGreaterThan(metrics.clientWidth);

            // Both ends of the hand are reachable: the first card starts inside the rail and the
            // last one comes into view once the rail is scrolled to its end.
            const railBox = await rail.boundingBox();
            const firstCard = await page.locator("#myHand .hand-card-btn").first().boundingBox();
            expect(firstCard.x).toBeGreaterThanOrEqual(railBox.x - 0.5);
            await rail.evaluate(el => { el.scrollLeft = el.scrollWidth; });
            const lastCard = await page.locator("#myHand .hand-card-btn").last().boundingBox();
            expect(lastCard.x + lastCard.width).toBeLessThanOrEqual(railBox.x + railBox.width + 0.5);
        });
    }

    /*
     * A fresh deal on a phone: long names, the bot "thinking" note and, with four players, the
     * opening-lead line above the table. The sticky action strip must not hide the hand.
     */
    async function openFreshDeal(page, {width, height, players}) {
        await page.setViewportSize({width, height});
        await seedSession(page);
        await serveGame(page, syntheticGame({players, botThinking: {p2: "planning attack..."}}));
        await page.goto("/");
        await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(6);
        await page.evaluate(() => window.scrollTo(0, 0));
    }

    async function handAndChrome(page) {
        const cards = [];
        for (const card of await page.locator("#myHand .hand-card-btn").all()) cards.push(await card.boundingBox());
        return {
            top: Math.min(...cards.map(c => c.y)),
            bottom: Math.max(...cards.map(c => c.y + c.height)),
            stripTop: (await page.locator(".action-strip").boundingBox()).y,
            headerBottom: await page.locator("#gameView .room-header").evaluate(el => el.getBoundingClientRect().bottom)
        };
    }

    for (const players of [2, 4]) {
        test(`a fresh ${players}-player hand is fully visible above the action strip at 375x812`, async ({ page }) => {
            await openFreshDeal(page, {width: 375, height: 812, players});
            const {top, bottom, stripTop} = await handAndChrome(page);
            expect(top).toBeGreaterThan(0);
            expect(bottom).toBeLessThanOrEqual(stripTop + 0.5);
        });
    }

    for (const players of [2, 3, 4]) {
        test(`at 375x667 a ${players}-player hand is at least half visible, and fully visible at max scroll`, async ({ page }) => {
            await openFreshDeal(page, {width: 375, height: 667, players});
            const atTop = await handAndChrome(page);
            expect(atTop.stripTop - atTop.top).toBeGreaterThanOrEqual((atTop.bottom - atTop.top) / 2);

            await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
            const atEnd = await handAndChrome(page);
            expect(atEnd.top).toBeGreaterThanOrEqual(atEnd.headerBottom - 0.5);
            expect(atEnd.bottom).toBeLessThanOrEqual(atEnd.stripTop + 0.5);
        });
    }

    test("swipes that start on a hand card can still scroll", async ({ page }) => {
        await openTable(page, {width: 375, players: 2, handSize: 18});
        const touchAction = await page.locator("#myHand .hand-card-btn").first()
            .evaluate(el => getComputedStyle(el).touchAction);
        expect(touchAction).not.toBe("none");
    });
});
