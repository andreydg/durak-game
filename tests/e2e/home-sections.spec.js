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

    test("Open tables shows no empty section while the first refresh is pending", async ({page}) => {
        let release;
        const pending = new Promise(resolve => {
            release = resolve;
        });
        await page.route("**/api/lobbies", async route => {
            await pending;
            await route.fulfill({status: 200, contentType: "application/json", body: JSON.stringify([table])});
        });

        await page.goto("/");
        await expect(page.locator("#lobbyOpenTables")).toBeHidden();

        release();
        await expect(page.locator(`.lobby-list-join[data-code="${table.code}"]`)).toBeVisible();
        await expect(page.locator("#lobbyOpenTables")).toBeVisible();
    });

    test("a failed refresh leaves Open tables shown or hidden as it was", async ({page}) => {
        const response = {status: 200, rows: [table]};
        await page.route("**/api/lobbies", route => route.fulfill({
            status: response.status,
            contentType: "application/json",
            body: JSON.stringify(response.rows)
        }));
        await page.goto("/");
        const notice = page.locator("#lobbyGameList").getByText("Could not load open tables. Try refreshing the page.");
        await page.evaluate(() => window.refreshLobbyLists());
        await expect(page.locator("#lobbyOpenTables")).toBeVisible();

        // Shown: the notice replaces the tables and the section stays.
        response.status = 503;
        expect(await page.evaluate(() => window.refreshLobbyLists())).toBe(false);
        await expect(page.locator("#lobbyOpenTables")).toBeVisible();
        await expect(notice).toBeVisible();
        expect(await page.evaluate(() => window.refreshLobbyLists())).toBe(false);
        await expect(notice).toBeVisible();

        // Hidden: it stays hidden.
        response.status = 200;
        response.rows = [];
        await page.evaluate(() => window.refreshLobbyLists());
        await expect(page.locator("#lobbyOpenTables")).toBeHidden();
        response.status = 503;
        expect(await page.evaluate(() => window.refreshLobbyLists())).toBe(false);
        await expect(page.locator("#lobbyOpenTables")).toBeHidden();

        // And the next good answer with a table shows it again.
        response.status = 200;
        response.rows = [table];
        await page.evaluate(() => window.refreshLobbyLists());
        await expect(page.locator(`.lobby-list-join[data-code="${table.code}"]`)).toBeVisible();
        await expect(notice).toHaveCount(0);
    });

    test("About Durak is the one guide on the page and opens on demand", async ({page}) => {
        await page.goto("/");
        const about = page.locator("details:has(#what-is-durak)");

        await expect(page.locator("#lobbyView details")).toHaveCount(1);
        await expect(about).toHaveJSProperty("open", false);
        await expect(page.getByRole("heading", {name: "About Durak"})).toBeVisible();
        await expect(page.getByText("Durak (Дурак) is a fast card shedding game")).toBeHidden();
        await expect(page.getByRole("link", {name: "Read the complete Durak rules →"})).toBeHidden();

        await about.locator("summary").click();
        await expect(about).toHaveJSProperty("open", true);
        await expect(page.getByText("Durak (Дурак) is a fast card shedding game for 2–4 players")).toBeVisible();
        await expect(page.getByRole("link", {name: "Read the complete Durak rules →"})).toBeVisible();

        await about.locator("summary").focus();
        await page.keyboard.press("Enter");
        await expect(about).toHaveJSProperty("open", false);
        await page.keyboard.press("Enter");
        await expect(about).toHaveJSProperty("open", true);
    });
});
