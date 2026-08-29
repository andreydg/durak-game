import { test, expect } from "@playwright/test";

test.describe("Lobby & room creation", () => {
    test("home page shows host and join controls", async ({ page }) => {
        await page.goto("/");
        await expect(page.locator("#lobbyView")).toBeVisible();
        await expect(page.locator("#createBtn")).toBeVisible();
        await expect(page.locator("#joinBtn")).toBeVisible();
        await expect(page.locator("#hostName")).toBeVisible();
        await expect(page.locator("#quickPlayBtn")).toHaveText("Quick Play vs Elektronik");
        const quickBox = await page.locator("#quickPlayBtn").boundingBox();
        const createBox = await page.locator("#createBtn").boundingBox();
        expect(quickBox?.y).toBeLessThan(createBox?.y ?? 0);
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
        await expect(page.locator("#visibilityLabel")).toHaveText("Public room");
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

    test("quick play starts a private game against a bot in one action", async ({ page }) => {
        await page.goto("/");
        await page.fill("#hostName", "Solo Player");
        await page.click("#quickPlayBtn");

        await expect(page.locator("#gameView")).toBeVisible();
        await expect(page.locator("#statusLabel")).toHaveText("In progress");
        await expect(page.locator("#visibilityLabel")).toHaveText("Invite only");
        await expect(page.locator("#roleLabel")).toContainText("Elektronik");
        await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(6);
        await expect(page.locator("#shareBtn")).toBeHidden();
    });

    test("a stale invite takes precedence without erasing the restored session", async ({ page }) => {
        await page.goto("/");
        await page.fill("#hostName", "Existing Player");
        await page.click("#createBtn");
        await expect(page.locator("#gameView")).toBeVisible();
        await expect(page.locator("#gameCodeLabel")).toHaveText(/^[A-Z0-9]{6}$/);
        const currentCode = (await page.locator("#gameCodeLabel").textContent())?.trim() ?? "";
        const invitedCode = currentCode === "ABC123" ? "XYZ789" : "ABC123";
        const savedSession = await page.evaluate(() => ({
            gameCode: sessionStorage.getItem("durak_game_code"),
            playerId: sessionStorage.getItem("durak_player_id"),
            playerToken: sessionStorage.getItem("durak_player_token")
        }));
        await page.route(`**/api/games/${invitedCode}/join`, route => route.fulfill({
            status: 404,
            contentType: "application/json",
            body: JSON.stringify({message: "Game not found"})
        }));

        await page.goto(`/?room=${invitedCode}`);

        await expect(page.locator("#lobbyView")).toBeVisible();
        await expect(page.locator("#gameView")).toBeHidden();
        await expect(page.locator("#gameCode")).toHaveValue(invitedCode);
        await expect(page.locator("#joinHint")).toContainText(`Invite loaded for room ${invitedCode}`);
        await page.click("#joinBtn");
        await expect(page.locator("#appAlert")).toContainText("Game not found");
        expect(await page.evaluate(() => ({
            gameCode: sessionStorage.getItem("durak_game_code"),
            playerId: sessionStorage.getItem("durak_player_id"),
            playerToken: sessionStorage.getItem("durak_player_token")
        }))).toEqual(savedSession);

        await page.goto("/");
        await expect(page.locator("#gameView")).toBeVisible();
        await expect(page.locator("#gameCodeLabel")).toHaveText(currentCode);
    });

    test("quick play consumes an invite URL so reload restores the new private game", async ({ page }) => {
        await page.goto("/?room=ABC123&utm_source=invite#play");
        await page.fill("#hostName", "Invite Quick Player");
        await page.click("#quickPlayBtn");
        await expect(page.locator("#gameView")).toBeVisible();
        const quickGameCode = (await page.locator("#gameCodeLabel").textContent())?.trim() ?? "";
        expect(quickGameCode).toMatch(/^[A-Z0-9]{6}$/);

        const consumedUrl = new URL(page.url());
        expect(consumedUrl.searchParams.has("room")).toBe(false);
        expect(consumedUrl.searchParams.get("utm_source")).toBe("invite");
        expect(consumedUrl.hash).toBe("#play");

        await page.reload();

        await expect(page.locator("#gameView")).toBeVisible();
        await expect(page.locator("#gameCodeLabel")).toHaveText(quickGameCode);
        await expect(page.locator("#visibilityLabel")).toHaveText("Invite only");
    });

    test("invite-only room stays out of discovery but its link pre-fills and joins", async ({ page, browser }) => {
        await page.goto("/");
        await page.fill("#hostName", "Private Host");
        await page.uncheck("#publicRoom");
        await page.click("#createBtn");
        await expect(page.locator("#visibilityLabel")).toHaveText("Invite only");
        const code = (await page.locator("#gameCodeLabel").textContent())?.trim() ?? "";

        const otherContext = await browser.newContext();
        const otherPage = await otherContext.newPage();
        const response = await otherPage.request.get("/api/lobbies");
        const listedCodes = (await response.json()).map(room => room.code);
        expect(listedCodes).not.toContain(code);

        await otherPage.goto(`/?room=${code.toLowerCase()}`);
        await expect(otherPage.locator("#gameCode")).toHaveValue(code);
        await expect(otherPage.locator("#joinHint")).toContainText(`Invite loaded for room ${code}`);
        await otherPage.fill("#playerName", "Invited Friend");
        await otherPage.click("#joinBtn");
        await expect(otherPage.locator("#gameView")).toBeVisible();
        await expect(otherPage.locator("#gameCodeLabel")).toHaveText(code);
        await expect(otherPage.locator("#visibilityLabel")).toHaveText("Invite only");
        await otherContext.close();
    });

    test("copy invite writes a durable room link to the clipboard", async ({ page, context }) => {
        await context.grantPermissions(["clipboard-read", "clipboard-write"]);
        await page.goto("/");
        await page.fill("#hostName", "Link Host");
        await page.click("#createBtn");
        await expect(page.locator("#gameCodeLabel")).toHaveText(/^[A-Z0-9]{6}$/);
        const code = (await page.locator("#gameCodeLabel").textContent())?.trim() ?? "";

        await page.click("#shareBtn");

        await expect(page.locator("#shareBtn")).toHaveText("Invite copied");
        const copied = await page.evaluate(() => navigator.clipboard.readText());
        expect(copied).toBe(new URL(`/?room=${code}`, page.url()).toString());
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
