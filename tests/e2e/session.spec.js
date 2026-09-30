import { test, expect } from "@playwright/test";
import { seedSession, syntheticGame } from "./support/synthetic.js";

async function createRoomWithBot(page, hostName) {
    await page.goto("/");
    await page.fill("#hostName", hostName);
    await page.click("#createBtn");
    await expect(page.locator("#gameView")).toBeVisible();
    await page.click("#addBotBtn");
    await expect(page.locator("#roleLabel")).toContainText("Elektronik", { timeout: 10_000 });
    return (await page.locator("#gameCodeLabel").textContent())?.trim() ?? "";
}

function collectWarnings(page) {
    const warnings = [];
    page.on("console", message => {
        if (message.type() === "warning") warnings.push(message.text());
    });
    return warnings;
}

test.describe("Saved seat validity", () => {
    test("a seat whose token the server no longer accepts offers a way back", async ({ page }) => {
        const code = await createRoomWithBot(page, "Token Host");
        await page.click("#startBtn");
        await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(6, { timeout: 10_000 });

        const warnings = collectWarnings(page);
        await page.evaluate(() => sessionStorage.setItem("durak_player_token", "not-my-token"));
        await page.reload();

        const notice = page.locator("#seatNotice");
        await expect(notice).toBeVisible();
        await expect(notice).toHaveAttribute("role", "alert");
        await expect(notice).toContainText(`This browser's seat in room ${code} is no longer valid`);
        expect(warnings.some(text => text.includes(code) && text.includes("no longer valid"))).toBe(true);

        await page.click("#seatNoticeLobbyBtn");
        await expect(page.locator("#lobbyView")).toBeVisible();
        await expect(page.locator("#gameCode")).toHaveValue(code);
        expect(await page.evaluate(() => sessionStorage.getItem("durak_player_token"))).toBe("");
    });

    test("a host whose token is rejected in the waiting room is told so", async ({ page }) => {
        const code = await createRoomWithBot(page, "Lobby Token Host");
        await page.evaluate(() => sessionStorage.setItem("durak_player_token", "not-my-token"));
        await page.reload();

        await expect(page.locator("#seatNotice")).toContainText(`room ${code} is no longer valid`);
    });

    test("any 403 from an action marks the seat invalid", async ({ page }) => {
        await seedSession(page);
        const game = syntheticGame({
            attackerPlayerId: "me",
            defenderPlayerId: "p2",
            legalMoves: {canAttack: true, attackableCardCodes: ["6C"]},
            hand: ["6C", "7D", "8H", "9S", "JC", "QD"]
        });
        await page.routeWebSocket("**/ws/games/**", () => {});
        await page.route("**/api/games/SYNTH2**", route => {
            const forbidden = route.request().method() === "POST";
            return route.fulfill({
                status: forbidden ? 403 : 200,
                contentType: "application/json",
                body: JSON.stringify(forbidden ? {message: "You are not authorized to act as this player."} : game)
            });
        });
        const warnings = collectWarnings(page);

        await page.goto("/");
        await page.locator('#myHand [data-card-code="6C"]').click();
        await page.click("#attackBtn");

        await expect(page.locator("#seatNotice")).toContainText("room SYNTH2 is no longer valid");
        await expect(page.locator("#appAlert")).toBeHidden();
        expect(warnings.some(text => text.includes("403"))).toBe(true);
    });

    test("a saved seat without a token is not a session", async ({ page }) => {
        const gameRequests = [];
        page.on("request", request => {
            if (request.url().includes("/api/games/")) gameRequests.push(request.url());
        });
        await page.addInitScript(() => {
            if (sessionStorage.getItem("seeded")) return;
            sessionStorage.setItem("seeded", "1");
            sessionStorage.setItem("durak_game_code", "HJK234");
            sessionStorage.setItem("durak_player_id", "legacy-player");
        });

        await page.goto("/");

        await expect(page.locator("#lobbyView")).toBeVisible();
        await page.waitForTimeout(300);
        expect(gameRequests).toEqual([]);
        expect(await page.evaluate(() => sessionStorage.getItem("durak_game_code"))).toBeNull();
    });

    test("a legacy localStorage seat is not adopted", async ({ page }) => {
        const gameRequests = [];
        page.on("request", request => {
            if (request.url().includes("/api/games/")) gameRequests.push(request.url());
        });
        await page.addInitScript(() => {
            localStorage.setItem("durak_game_code", "HJK234");
            localStorage.setItem("durak_player_id", "legacy-player");
        });

        await page.goto("/");

        await expect(page.locator("#lobbyView")).toBeVisible();
        await page.waitForTimeout(300);
        expect(gameRequests).toEqual([]);
        expect(await page.evaluate(() => sessionStorage.getItem("durak_game_code"))).toBeNull();
    });
});
