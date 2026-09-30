import { test, expect } from "@playwright/test";
import { seedSession, syntheticGame } from "./support/synthetic.js";

/** Holds matching requests for `delayMs` so a second activation lands while the first is pending. */
async function slowDown(page, pattern, delayMs = 400) {
    await page.route(pattern, async route => {
        await new Promise(resolve => setTimeout(resolve, delayMs));
        await route.continue();
    });
}

function countRequests(page, predicate) {
    const seen = [];
    page.on("request", request => {
        if (predicate(request)) seen.push(request.url());
    });
    return seen;
}

test.describe("Repeat activations while a request is pending", () => {
    test("double-clicking Join in Open tables takes exactly one seat", async ({ page, browser }) => {
        await page.goto("/");
        await page.fill("#hostName", "Table Host");
        await page.click("#createBtn");
        await expect(page.locator("#gameCodeLabel")).toHaveText(/^[A-Z0-9]{6}$/);
        const code = (await page.locator("#gameCodeLabel").textContent()).trim();

        const guestContext = await browser.newContext();
        const guest = await guestContext.newPage();
        const joins = countRequests(guest, request =>
            request.method() === "POST" && new URL(request.url()).pathname === `/api/games/${code}/join`);
        await slowDown(guest, `**/api/games/${code}/join`);
        await guest.goto("/");
        const joinButton = guest.locator(`.lobby-list-join[data-code="${code}"]`);
        await expect(joinButton).toBeVisible({ timeout: 15_000 });

        await joinButton.dblclick();

        await expect(guest.locator("#gameView")).toBeVisible();
        await expect(guest.locator("#gameCodeLabel")).toHaveText(code);
        await guest.waitForTimeout(600);
        expect(joins).toHaveLength(1);

        const room = await (await page.request.get(`/api/games/${code}`)).json();
        expect(room.players).toHaveLength(2);
        await guestContext.close();
    });

    test("double-clicking Quick Play creates one game", async ({ page }) => {
        const creates = countRequests(page, request =>
            request.method() === "POST" && new URL(request.url()).pathname === "/api/games/quick-play");
        await slowDown(page, "**/api/games/quick-play");
        await page.goto("/");

        await page.locator("#quickPlayBtn").dblclick();

        await expect(page.locator("#gameView")).toBeVisible();
        await page.waitForTimeout(600);
        expect(creates).toHaveLength(1);
    });

    test("double-clicking Take cards sends one request and shows no error", async ({ page }) => {
        await seedSession(page);
        const defending = syntheticGame({
            version: 20,
            table: [{attackCard: "9H"}],
            hand: ["6C", "7D", "8H", "JS", "QC", "KD"],
            legalMoves: {canTake: true, canDefend: true, defensesByAttackCard: {"9H": ["JS"]}}
        });
        const taking = syntheticGame({
            version: 21,
            table: [{attackCard: "9H"}],
            hand: ["6C", "7D", "8H", "JS", "QC", "KD"],
            takingCardsInProgress: true,
            takingPlayerId: "me"
        });
        let current = defending;
        const takes = [];
        await page.routeWebSocket("**/ws/games/**", () => {});
        await page.route("**/api/games/SYNTH2**", async route => {
            const request = route.request();
            if (request.method() === "POST" && new URL(request.url()).pathname.endsWith("/take")) {
                takes.push(request.url());
                await new Promise(resolve => setTimeout(resolve, 400));
                current = taking;
                // A second take would be rejected by the real server like this.
                const status = takes.length > 1 ? 409 : 200;
                await route.fulfill({
                    status,
                    contentType: "application/json",
                    body: JSON.stringify(status === 200 ? taking : {message: "Defender is already taking"})
                });
                return;
            }
            await route.fulfill({status: 200, contentType: "application/json", body: JSON.stringify(current)});
        });

        await page.goto("/");
        await expect(page.locator("#takeBtn")).toBeEnabled();

        await page.locator("#takeBtn").dblclick();

        await expect(page.locator("#battleTableBanner")).toContainText("is taking cards");
        await page.waitForTimeout(600);
        expect(takes).toHaveLength(1);
        await expect(page.locator("#appAlert")).toBeHidden();
    });

    test("pending actions disable the action strip and the hand", async ({ page }) => {
        await seedSession(page);
        const game = syntheticGame({
            attackerPlayerId: "me",
            defenderPlayerId: "p2",
            hand: ["6C", "7D", "8H", "9S", "JC", "QD"],
            legalMoves: {canAttack: true, attackableCardCodes: ["6C", "7D", "8H", "9S", "JC", "QD"]}
        });
        let release;
        const held = new Promise(resolve => { release = resolve; });
        await page.routeWebSocket("**/ws/games/**", () => {});
        await page.route("**/api/games/SYNTH2**", async route => {
            if (route.request().method() === "POST") {
                await held;
            }
            await route.fulfill({status: 200, contentType: "application/json", body: JSON.stringify(game)});
        });

        await page.goto("/");
        await page.locator('#myHand [data-card-code="6C"]').click();
        await page.click("#attackBtn");

        await expect(page.locator("#myHand .hand-card-btn").first()).toHaveAttribute("aria-disabled", "true");
        await expect(page.locator('#myHand [data-card-code="7D"]')).toHaveAttribute("draggable", "false");
        await expect(page.locator("#attackBtn")).toHaveAttribute("aria-disabled", "true");
        // Selecting another card is ignored while the attack is pending (force: Playwright itself
        // refuses to click an aria-disabled button).
        await page.locator('#myHand [data-card-code="7D"]').click({force: true});
        await expect(page.locator('#myHand [data-card-code="7D"]')).toHaveAttribute("aria-pressed", "false");

        release();
        await expect(page.locator("#myHand .hand-card-btn").first()).not.toHaveAttribute("aria-disabled", "true");
    });
});
