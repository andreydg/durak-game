import {test, expect} from "@playwright/test";

const table = {code: "WXYZ23", playerNames: ["Host"], playerCount: 1, maxPlayers: 4};

/** Serves /api/lobbies from `rows.current`, so a test can change what the server lists. */
async function routeLobbies(page, rows) {
    await page.route("**/api/lobbies", route => route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(rows.current)
    }));
}

test.describe("Home page sections", () => {
    test("Open tables is shown only while there is a table to join", async ({page}) => {
        const rows = {current: []};
        await routeLobbies(page, rows);
        await page.goto("/");
        await page.evaluate(() => window.refreshLobbyLists());
        await expect(page.locator("#lobbyOpenTables")).toBeHidden();

        rows.current = [table];
        await page.evaluate(() => window.refreshLobbyLists());
        await expect(page.locator("#lobbyOpenTables")).toBeVisible();
        await expect(page.locator(`.lobby-list-join[data-code="${table.code}"]`)).toBeVisible();

        rows.current = [];
        await page.evaluate(() => window.refreshLobbyLists());
        await expect(page.locator("#lobbyOpenTables")).toBeHidden();
    });

    test("when the last table goes away under keyboard focus, focus moves to the lobby heading", async ({page}) => {
        const rows = {current: [table]};
        await routeLobbies(page, rows);
        await page.goto("/");
        await page.evaluate(() => window.refreshLobbyLists());
        await page.locator(`.lobby-list-join[data-code="${table.code}"]`).focus();

        rows.current = [];
        await page.evaluate(() => window.refreshLobbyLists());

        await expect(page.locator("#lobbyOpenTables")).toBeHidden();
        await expect(page.locator("#lobbyHeading")).toBeFocused();
    });

    test("What is Durak and Online play start collapsed and open on demand", async ({page}) => {
        await page.goto("/");
        const intro = page.locator("details:has(#what-is-durak)");
        const play = page.locator("details:has(#how-it-works)");

        for (const section of [intro, play]) {
            await expect(section).toHaveJSProperty("open", false);
        }
        await expect(page.getByRole("heading", {name: "What is Durak"})).toBeVisible();
        await expect(page.getByRole("heading", {name: "Online play"})).toBeVisible();
        await expect(page.getByText("Durak (Дурак) is a fast card shedding game")).toBeHidden();
        await expect(page.getByRole("link", {name: "Read the complete Durak rules →"})).toBeHidden();

        await intro.locator("summary").click();
        await expect(intro).toHaveJSProperty("open", true);
        await expect(page.getByText("Durak (Дурак) is a fast card shedding game")).toBeVisible();
        await expect(play).toHaveJSProperty("open", false);

        await play.locator("summary").focus();
        await page.keyboard.press("Enter");
        await expect(play).toHaveJSProperty("open", true);
        await expect(page.getByRole("link", {name: "Read the complete Durak rules →"})).toBeVisible();
        await page.keyboard.press("Enter");
        await expect(play).toHaveJSProperty("open", false);
    });
});
