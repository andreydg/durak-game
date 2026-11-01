import {test, expect} from "@playwright/test";
import {readFileSync} from "node:fs";

/*
 * A deploy or rollback between loading the home page and loading its modules can pair this build's
 * files with the previous release's: ?v= is a cache key, not a version the server keeps, so every
 * URL returns the running release's bytes. fixtures/release-c496441 holds that release's index.html,
 * lobby.js and dom.js byte for byte (c496441, the last release before Open tables became
 * conditional). Whatever the mix, a listed table must be visible and joinable.
 */
const previous = file => readFileSync(new URL(`./fixtures/release-c496441/${file}`, import.meta.url), "utf8");
const table = {code: "WXYZ23", playerNames: ["Host"], playerCount: 1, maxPlayers: 4};

/** Serves /api/lobbies from `rows.current`, so a test can change what the server lists. */
async function routeLobbies(page, rows) {
    await page.route("**/api/lobbies", route => route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(rows.current)
    }));
}

/** Answers every request for /js/<file> (any ?v=) with the previous release's copy; counts them. */
async function servePreviousModule(page, file) {
    const served = {count: 0};
    await page.route(url => url.pathname === `/js/${file}`, route => {
        served.count++;
        return route.fulfill({status: 200, contentType: "text/javascript; charset=utf-8", body: previous(file)});
    });
    return served;
}

/** Answers the home page with the previous release's HTML (a cached page brings its own headers). */
async function servePreviousPage(page) {
    await page.route(url => url.pathname === "/", route => route.fulfill({
        status: 200,
        contentType: "text/html; charset=utf-8",
        body: previous("index.html")
    }));
}

function collectPageErrors(page) {
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    return errors;
}

/** Empty, then one table, then empty again: the table is visible and the empty list never is. */
async function expectTablesShownOnlyWhileListed(page, rows) {
    const openTables = page.locator("#lobbyOpenTables");
    await page.evaluate(() => window.refreshLobbyLists());
    await expect(openTables).toBeHidden();

    rows.current = [table];
    await page.evaluate(() => window.refreshLobbyLists());
    await expect(openTables).toBeVisible();
    await expect(page.locator(`.lobby-list-join[data-code="${table.code}"]`)).toBeVisible();

    rows.current = [];
    await page.evaluate(() => window.refreshLobbyLists());
    await expect(openTables).toBeHidden();
}

test.describe("Open tables across releases", () => {
    for (const mix of [
        {name: "this HTML with the previous lobby.js", previousModules: ["lobby.js"]},
        {name: "this HTML with the previous lobby.js and dom.js", previousModules: ["lobby.js", "dom.js"]},
        {name: "this HTML and lobby.js with the previous dom.js", previousModules: ["dom.js"]}
    ]) {
        test(`${mix.name} shows a listed table`, async ({page}) => {
            const errors = collectPageErrors(page);
            const served = {};
            for (const file of mix.previousModules) {
                served[file] = await servePreviousModule(page, file);
            }
            const rows = {current: [table]};
            await routeLobbies(page, rows);

            await page.goto("/");
            await expect(page.locator("details:has(#what-is-durak)")).toHaveCount(1);
            await expect(page.locator(`.lobby-list-join[data-code="${table.code}"]`)).toBeVisible();
            await expect(page.locator("#lobbyOpenTables")).toBeVisible();
            if (mix.previousModules.includes("lobby.js")) {
                // The previous renderer's own empty message: proof that its lobby.js is the one running.
                rows.current = [];
                await page.evaluate(() => window.refreshLobbyLists());
                await expect(page.locator("#lobbyGameList")).toContainText("No open tables yet");
            }
            rows.current = [];
            await expectTablesShownOnlyWhileListed(page, rows);

            for (const file of mix.previousModules) {
                expect(served[file].count, `${file} came from the previous release`).toBeGreaterThan(0);
            }
            expect(errors).toEqual([]);
        });
    }

    test("the previous HTML with this release's modules shows a listed table", async ({page}) => {
        const errors = collectPageErrors(page);
        await servePreviousPage(page);
        const rows = {current: []};
        await routeLobbies(page, rows);

        await page.goto("/");
        // The previous page: its guides are sections, not disclosures.
        await expect(page.locator("section[aria-labelledby='what-is-durak']")).toHaveCount(1);
        await expect(page.locator("details")).toHaveCount(0);
        await expectTablesShownOnlyWhileListed(page, rows);
        expect(errors).toEqual([]);
    });
});
