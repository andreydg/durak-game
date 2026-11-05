import {test, expect} from "@playwright/test";

async function expectQuickPlayMarkup(page) {
    const quickPlay = page.locator("#quickPlayBtn");
    await expect(quickPlay).toBeVisible();
    await expect(quickPlay).not.toHaveAttribute("aria-busy", "true");
    await expect(quickPlay).toHaveAccessibleName("Quick Play vs Elektronik");
    await expect(quickPlay.locator("svg")).toHaveCount(1);
    await expect(quickPlay.locator("svg")).toHaveAttribute("aria-hidden", "true");
    const arrow = quickPlay.locator("span.lobby-quick-arrow");
    await expect(arrow).toHaveCount(1);
    await expect(arrow).toHaveText("→");
    await expect(arrow).toHaveAttribute("aria-hidden", "true");
}

test.describe("Editorial lobby action markup", () => {
    test("Quick Play preserves its markup after a one-time 503", async ({page}) => {
        let failNextPost = true;
        await page.route("**/api/games/quick-play", async route => {
            if (failNextPost && route.request().method() === "POST") {
                failNextPost = false;
                await route.fulfill({status: 503, json: {message: "Temporary outage"}});
                return;
            }
            await route.continue();
        });
        await page.goto("/");
        await page.locator("#quickPlayBtn").click();

        await expect(page.locator("#appAlert")).toBeVisible();
        await expect(page.locator("#appAlert")).toContainText("Quick play: Temporary outage");
        await expectQuickPlayMarkup(page);
    });

    test("Create room preserves its markup after creating a real room and leaving", async ({page}) => {
        await page.goto("/");
        const create = page.locator("#createBtn");
        await create.click();
        await expect(page.locator("#gameView")).toBeVisible();
        await expect(page.locator("#statusLabel")).toHaveText("Lobby");
        await expect(create).not.toHaveAttribute("aria-busy", "true");

        await page.locator("#leaveBtn").click();

        await expect(page.locator("#lobbyView")).toBeVisible();
        await expect(page.locator("#gameView")).toBeHidden();
        await expect(page.locator("#appAlert")).toBeHidden();
        await expect(create).toBeVisible();
        await expect(create).toHaveAccessibleName("Create room");
        const plus = create.locator("span.lobby-plus");
        await expect(plus).toHaveCount(1);
        await expect(plus).toHaveText("+");
        await expect(plus).toHaveAttribute("aria-hidden", "true");
    });

    test("Quick Play preserves its markup after a real game and confirmed leave", async ({page}) => {
        await page.goto("/");
        await page.locator("#quickPlayBtn").click();
        await expect(page.locator("#gameView")).toBeVisible();
        await expect(page.locator("#statusLabel")).toHaveText("In progress");
        await expect(page.locator("#roleLabel")).toContainText("Elektronik");
        await expect(page.locator("#quickPlayBtn")).not.toHaveAttribute("aria-busy", "true");

        await page.locator("#leaveBtn").click();
        const dialog = page.getByRole("dialog", {name: "Leave this game?"});
        await expect(dialog).toBeVisible();
        await dialog.getByRole("button", {name: "Leave game", exact: true}).click();

        await expect(dialog).toBeHidden();
        await expect(page.locator("#lobbyView")).toBeVisible();
        await expect(page.locator("#gameView")).toBeHidden();
        await expect(page.locator("#appAlert")).toBeHidden();
        await expectQuickPlayMarkup(page);
    });
});
