import { test, expect } from "@playwright/test";

test.describe("Lobby & room creation", () => {
    test("home page shows host and join controls", async ({ page }) => {
        await page.goto("/");
        await expect(page.locator("#lobbyView")).toBeVisible();
        await expect(page.locator("#createBtn")).toBeVisible();
        await expect(page.locator("#joinBtn")).toBeVisible();
        await expect(page.locator("#hostName")).toBeVisible();
    });

    test("invalid room code shows an accessible error", async ({ page }) => {
        await page.goto("/");
        await page.fill("#gameCode", "NOPE12");
        await page.click("#joinBtn");

        await expect(page.locator("#appAlert")).toBeVisible();
        await expect(page.locator("#appAlert")).toContainText("Game not found");
        await expect(page.locator("#appAlert")).toHaveAttribute("role", "alert");
    });

    test("error alert is opaque and cannot block controls behind it", async ({ page }) => {
        await page.goto("/");
        await page.fill("#gameCode", "NOPE12");
        await page.click("#joinBtn");

        const alert = page.locator("#appAlert");
        await expect(alert).toBeVisible();
        const styles = await alert.evaluate(element => {
            const computed = getComputedStyle(element);
            return {
                backgroundColor: computed.backgroundColor,
                pointerEvents: computed.pointerEvents
            };
        });
        expect(styles.backgroundColor).not.toMatch(/rgba\([^)]*,\s*0(?:\.|\))/);
        expect(styles.backgroundColor).not.toMatch(/\/\s*0(?:\.|\s|$)/);
        expect(styles.pointerEvents).toBe("none");
    });

    test("join form submits with Enter", async ({ page }) => {
        await page.goto("/");
        await page.fill("#gameCode", "NOPE12");
        await page.press("#gameCode", "Enter");

        await expect(page.locator("#appAlert")).toContainText("Game not found");
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

    test("a successful refresh clears a transient connection error", async ({ page }) => {
        await page.goto("/");
        await page.fill("#hostName", "Recovery Host");
        await page.click("#createBtn");
        await expect(page.locator("#gameView")).toBeVisible();
        await expect(page.locator("#gameCodeLabel")).toHaveText(/^[A-Z0-9]{6}$/);
        const code = (await page.locator("#gameCodeLabel").textContent())?.trim() ?? "";
        expect(code).toMatch(/^[A-Z0-9]{6}$/);
        await page.evaluate(() => window.stopPolling());

        let rejectNextRead = true;
        await page.route(`**/api/games/${code}?*`, async route => {
            if (rejectNextRead && route.request().method() === "GET") {
                rejectNextRead = false;
                await route.fulfill({
                    status: 503,
                    contentType: "application/json",
                    body: JSON.stringify({message: "Temporary outage"})
                });
                return;
            }
            await route.continue();
        });

        await page.evaluate(() => window.refreshGame(false));
        await expect(page.locator("#appAlert")).toContainText("Temporary outage");

        await page.evaluate(() => window.refreshGame(false));
        await expect(page.locator("#appAlert")).toBeHidden();
    });

    test("a successful background refresh preserves an action error", async ({ page }) => {
        await page.goto("/");
        await page.fill("#hostName", "Action Error Host");
        await page.click("#createBtn");
        await expect(page.locator("#gameView")).toBeVisible();
        await page.evaluate(() => window.stopPolling());

        await page.evaluate(() => window.runAction(
            "Attack",
            () => Promise.reject(new Error("Not your turn"))
        ));
        const alert = page.locator("#appAlert");
        await expect(alert).toContainText("Attack: Not your turn");
        await expect(alert).toHaveAttribute("data-kind", "action");

        await page.evaluate(() => window.refreshGame(false));

        await expect(alert).toContainText("Attack: Not your turn");
        await expect(alert).toBeVisible();
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
