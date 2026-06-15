import { test, expect } from "@playwright/test";

test.describe("Hand privacy (anti-cheat)", () => {
    test("a hand cannot be read from the API without the owner's token", async ({ page }) => {
        await page.goto("/");
        await page.fill("#hostName", "Alice");
        await page.click("#createBtn");
        await expect(page.locator("#gameView")).toBeVisible();
        await page.click("#addBotBtn");
        await expect(page.locator("#roleLabel")).toContainText("Elektronik", { timeout: 10_000 });
        await page.click("#startBtn");
        await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(6, { timeout: 10_000 });

        const code = (await page.locator("#gameCodeLabel").textContent())?.trim() ?? "";
        const { playerId, token } = await page.evaluate(() => ({
            playerId: sessionStorage.getItem("durak_player_id"),
            token: sessionStorage.getItem("durak_player_token")
        }));
        expect(token).toBeTruthy();

        // Forged request (correct viewerPlayerId, no token): the hand must stay hidden.
        const anon = await page.request.get(`/api/games/${code}?viewerPlayerId=${playerId}`);
        const anonMe = (await anon.json()).players.find(p => p.id === playerId);
        expect(anonMe.hand.length).toBe(0);

        // Authorized request (matching token): the owner sees their six cards.
        const authed = await page.request.get(`/api/games/${code}?viewerPlayerId=${playerId}`, {
            headers: { "X-Durak-Token": token }
        });
        const authedMe = (await authed.json()).players.find(p => p.id === playerId);
        expect(authedMe.hand.length).toBe(6);
    });

    test("acting as another player without their token is rejected with 403", async ({ page }) => {
        await page.goto("/");
        await page.fill("#hostName", "Alice");
        await page.click("#createBtn");
        await expect(page.locator("#gameView")).toBeVisible();
        const code = (await page.locator("#gameCodeLabel").textContent())?.trim() ?? "";
        const { playerId } = await page.evaluate(() => ({
            playerId: sessionStorage.getItem("durak_player_id")
        }));

        // Try to start the game as the host but with a bogus token.
        const res = await page.request.post(`/api/games/${code}/start`, {
            headers: { "X-Durak-Token": "forged-token" },
            data: { playerId }
        });
        expect(res.status()).toBe(403);
    });
});
