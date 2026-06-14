import { test, expect } from "@playwright/test";

async function createRoom(page, hostName = "Alice") {
    await page.goto("/");
    await page.fill("#hostName", hostName);
    await page.click("#createBtn");
    await expect(page.locator("#gameView")).toBeVisible();
    return (await page.locator("#gameCodeLabel").textContent())?.trim() ?? "";
}

test.describe("Gameplay against a bot", () => {
    test("host can add a bot, which appears as a second seated player", async ({ page }) => {
        await createRoom(page);
        await expect(page.locator("#addBotBtn")).toBeVisible();
        await page.click("#addBotBtn");

        // Two players now; the bot name carries the Elektronik suffix.
        await expect(page.locator("#roleLabel")).toContainText("Elektronik", { timeout: 10_000 });
    });

    test("starting a 2-player game deals six cards and reveals the trump", async ({ page }) => {
        await createRoom(page);
        await page.click("#addBotBtn");
        await expect(page.locator("#roleLabel")).toContainText("Elektronik", { timeout: 10_000 });

        await expect(page.locator("#startBtn")).toBeVisible();
        await page.click("#startBtn");

        await expect(page.locator("#statusLabel")).toHaveText("In progress", { timeout: 10_000 });
        await expect(page.locator("#playingArea")).toBeVisible();
        // My hand is dealt six cards.
        await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(6, { timeout: 10_000 });
        // Trump suit indicator is shown.
        await expect(page.locator("#trumpSuitHud")).toBeVisible();
    });

    test("selecting a hand card toggles its selected state and updates the hint", async ({ page }) => {
        await createRoom(page);
        await page.click("#addBotBtn");
        await expect(page.locator("#roleLabel")).toContainText("Elektronik", { timeout: 10_000 });
        await page.click("#startBtn");
        await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(6, { timeout: 10_000 });

        const firstCard = page.locator("#myHand .hand-card-btn").first();
        await firstCard.click();
        await expect(firstCard).toHaveClass(/selected/);
        await expect(page.locator("#actionHint")).not.toBeEmpty();

        // Clicking again deselects.
        await firstCard.click();
        await expect(firstCard).not.toHaveClass(/selected/);
    });

    test("a card reaches the table — human leads or the bot opens the attack", async ({ page }) => {
        await createRoom(page, "Alice");
        await page.click("#addBotBtn");
        await expect(page.locator("#roleLabel")).toContainText("Elektronik", { timeout: 10_000 });
        await page.click("#startBtn");
        await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(6, { timeout: 10_000 });

        // The first attacker is chosen by lowest trump — could be either player. Select my
        // first card so the Attack button reflects whether I'm the opener.
        const pair = page.locator("#battleCards .battle-pair").first();
        const attackBtn = page.locator("#attackBtn");
        await page.locator("#myHand .hand-card-btn").first().click();

        // Resolve to either "I can open" or "the bot already placed a card".
        await expect
            .poll(async () => (await pair.count()) > 0 || (await attackBtn.isEnabled()), { timeout: 20_000 })
            .toBe(true);

        if ((await pair.count()) === 0 && (await attackBtn.isEnabled())) {
            await attackBtn.click();
        }
        // Either my attack or the bot's opening attack lands a card on the felt.
        await expect(pair).toBeVisible({ timeout: 20_000 });
    });
});
