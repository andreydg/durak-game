import { test, expect } from "@playwright/test";

test.describe("Lobby & room creation", () => {
    test("home page shows host and join controls", async ({ page }) => {
        await page.goto("/");
        await expect(page.locator("#lobbyView")).toBeVisible();
        await expect(page.locator("#createBtn")).toBeVisible();
        await expect(page.locator("#joinBtn")).toBeVisible();
        await expect(page.locator("#hostName")).toBeVisible();
    });

    test("creating a game opens a room with a 6-char code and Lobby status", async ({ page }) => {
        await page.goto("/");
        await page.fill("#hostName", "Alice");
        await page.click("#createBtn");

        await expect(page.locator("#gameView")).toBeVisible();
        await expect(page.locator("#lobbyView")).toBeHidden();

        const code = (await page.locator("#gameCodeLabel").textContent())?.trim() ?? "";
        expect(code).toMatch(/^[A-Z0-9]{6}$/);

        await expect(page.locator("#statusLabel")).toHaveText("Lobby");
        await expect(page.locator("#roleLabel")).toContainText("Alice");
        await expect(page.locator("#roomWaitingLine")).toBeVisible();
    });

    test("a created table appears in Open tables for another visitor", async ({ page, browser }) => {
        await page.goto("/");
        await page.fill("#hostName", "Hostess");
        await page.click("#createBtn");
        await expect(page.locator("#gameView")).toBeVisible();
        await expect(page.locator("#gameCodeLabel")).toHaveText(/^[A-Z0-9]{6}$/);
        const code = (await page.locator("#gameCodeLabel").textContent())?.trim() ?? "";

        // A second, independent visitor (separate session storage) sees the open table.
        const otherContext = await browser.newContext();
        const otherPage = await otherContext.newPage();
        await otherPage.goto("/");
        await expect(otherPage.locator("#lobbyGameList")).toContainText(code, { timeout: 15_000 });
        await otherContext.close();
    });
});
