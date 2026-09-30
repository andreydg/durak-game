import { test, expect } from "@playwright/test";
import { contentSecurityPolicy } from "../support/csp.js";

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

    test("the UI works under a script-src 'self' content security policy", async ({ page }) => {
        // The policy the backend sends: 'self' plus the hash of the page's one import map.
        let policy = null;
        await page.route(url => url.pathname === "/", async route => {
            const response = await route.fetch();
            policy = contentSecurityPolicy(await response.text());
            await route.fulfill({response, headers: {...response.headers(), "content-security-policy": policy}});
        });
        await page.addInitScript(() => {
            window.__cspViolations = [];
            document.addEventListener("securitypolicyviolation", event => {
                window.__cspViolations.push(`${event.violatedDirective} ${event.blockedURI} ${event.sample}`);
            });
        });

        await page.goto("/");
        await page.fill("#hostName", "Policy Host");
        await page.click("#createBtn");
        await expect(page.locator("#gameView")).toBeVisible();
        await page.click("#addBotBtn");
        await expect(page.locator("#roleLabel")).toContainText("Elektronik", { timeout: 10_000 });
        await page.click("#startBtn");
        await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(6, { timeout: 10_000 });
        await page.locator("#myHand .hand-card-btn").first().click();
        await page.click("#helpToggleBtn");
        await expect(page.locator("#gameplayHint")).toBeVisible();

        expect(policy).toMatch(/^script-src 'self' 'sha256-[A-Za-z0-9+/]{43}='; style-src 'self' 'unsafe-inline'$/);
        expect(await page.evaluate(() => window.__cspViolations)).toEqual([]);
    });

    test("without the import map's hash the policy blocks the page's modules", async ({ page }) => {
        // Guards the check above: the hash really is what lets the import map (and so the game) run.
        await page.route(url => url.pathname === "/", async route => {
            const response = await route.fetch();
            await route.fulfill({
                response,
                headers: {...response.headers(), "content-security-policy": "script-src 'self'; style-src 'self' 'unsafe-inline'"}
            });
        });
        await page.addInitScript(() => {
            window.__cspViolations = [];
            document.addEventListener("securitypolicyviolation", event => window.__cspViolations.push(event.violatedDirective));
        });

        await page.goto("/");

        await expect.poll(() => page.evaluate(() => window.__cspViolations)).toContain("script-src-elem");
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
