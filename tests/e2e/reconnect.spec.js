import { test, expect } from "@playwright/test";
import { seedSession, syntheticGame } from "./support/synthetic.js";

const CODE = "SYNTH2";

/** Serves the saved seat's game, holding GETs until `release()` and answering via `answer`. */
async function holdRefresh(page, answer) {
    let release;
    const held = new Promise(resolve => { release = resolve; });
    await seedSession(page);
    await page.routeWebSocket("**/ws/games/**", () => {});
    await page.route(`**/api/games/${CODE}**`, async route => {
        await held;
        await answer(route);
    });
    return () => release();
}

test.describe("Restoring a saved seat after a reload", () => {
    test("shows a reconnecting state instead of clickable lobby actions", async ({ page }) => {
        const release = await holdRefresh(page, route => route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify(syntheticGame())
        }));

        await page.goto("/");

        const status = page.getByRole("status").filter({hasText: "Reconnecting"});
        await expect(status).toHaveText(`Reconnecting to room ${CODE}…`);
        await expect(page.locator("#lobbyView")).toBeHidden();
        await expect(page.locator("#quickPlayBtn")).toBeHidden();
        await expect(page.locator("#createBtn")).toBeHidden();

        release();
        await expect(page.locator("#playingArea")).toBeVisible();
        await expect(page.locator("#reconnectView")).toBeHidden();
        await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(6);
    });

    test("a failed reconnect falls back to the lobby, keeps the seat and says so", async ({ page }) => {
        const release = await holdRefresh(page, route => route.fulfill({
            status: 503,
            contentType: "application/json",
            body: JSON.stringify({message: "Game storage is temporarily unavailable. Please try again."})
        }));
        await page.goto("/");
        await expect(page.locator("#reconnectView")).toBeVisible();

        release();

        await expect(page.locator("#lobbyView")).toBeVisible();
        await expect(page.locator("#reconnectView")).toBeHidden();
        await expect(page.locator("#appAlert")).toContainText(`Could not reconnect to room ${CODE}`);
        await expect(page.locator("#quickPlayBtn")).toBeEnabled();
        expect(await page.evaluate(() => sessionStorage.getItem("durak_game_code"))).toBe(CODE);
    });

    test("a vanished room falls back to the lobby and forgets the seat", async ({ page }) => {
        const release = await holdRefresh(page, route => route.fulfill({
            status: 404,
            contentType: "application/json",
            body: JSON.stringify({message: "Game not found"})
        }));
        await page.goto("/");
        release();

        await expect(page.locator("#lobbyView")).toBeVisible();
        await expect(page.locator("#appAlert")).toContainText(`Room ${CODE} no longer exists.`);
        expect(await page.evaluate(() => sessionStorage.getItem("durak_game_code"))).toBe("");
    });
});
