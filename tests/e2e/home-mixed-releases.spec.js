import {test, expect} from "@playwright/test";
import {readFileSync} from "node:fs";

/*
 * The home page now has one "Playing as" field where the previous page had a name field in each
 * form. A deploy or rollback between loading the page and loading its modules can pair this build's
 * HTML with the previous release's scripts, or the reverse: ?v= is a cache key, not a version the
 * server keeps, so every URL returns the running release's bytes. fixtures/release-12acae6 holds
 * that release's index.html, main.js, actions.js and dom.js byte for byte (12acae6, the last
 * release with two name fields). Whatever the mix, nothing may throw, and rooms can still be
 * created and joined.
 */
const previous = file => readFileSync(new URL(`./fixtures/release-12acae6/${file}`, import.meta.url), "utf8");

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

/** Answers joins to ZZZZ22 with "Game not found" and records what each one sent. */
async function recordJoins(page) {
    const joins = [];
    await page.route("**/api/games/ZZZZ22/join", route => {
        joins.push(route.request().postDataJSON());
        return route.fulfill({status: 404, contentType: "application/json", body: JSON.stringify({message: "Game not found"})});
    });
    return joins;
}

test.describe("Home page across releases", () => {
    test("this HTML with the previous main.js, actions.js and dom.js creates and joins rooms", async ({page}) => {
        const errors = collectPageErrors(page);
        const served = {};
        for (const file of ["main.js", "actions.js", "dom.js"]) {
            served[file] = await servePreviousModule(page, file);
        }
        const joins = await recordJoins(page);

        // The previous main.js fills an invite's code and focuses its join-form name field, now hidden.
        await page.goto("/?room=zzzz22");
        await expect(page.locator("#gameCode")).toHaveValue("ZZZZ22");
        await expect(page.locator("#joinHint")).toContainText("Invite loaded for room ZZZZ22");

        // The previous actions.js joins with that hidden field's empty name: the server picks one.
        await page.fill("#hostName", "Alice");
        await page.click("#joinBtn");
        await expect(page.locator("#appAlert")).toContainText("Game not found");
        expect(joins).toEqual([{playerName: ""}]);

        // Creating a room reads the shared field, as it always did.
        await page.click("#createBtn");
        await expect(page.locator("#gameView")).toBeVisible();
        await expect(page.locator("#roleLabel")).toContainText("Alice");

        for (const file of Object.keys(served)) {
            expect(served[file].count, `${file} came from the previous release`).toBeGreaterThan(0);
        }
        expect(errors).toEqual([]);
    });

    test("the previous HTML with this release's modules joins with its own name field", async ({page}) => {
        const errors = collectPageErrors(page);
        await servePreviousPage(page);
        const joins = await recordJoins(page);

        // The previous page has a name field in the join form, and an invite puts the cursor there.
        await page.goto("/?room=zzzz22");
        await expect(page.locator("#playerName")).toBeVisible();
        await expect(page.locator("#playerName")).toBeFocused();
        await page.fill("#playerName", "Bob");
        await page.click("#joinBtn");
        await expect(page.locator("#appAlert")).toContainText("Game not found");
        expect(joins).toEqual([{playerName: "Bob"}]);

        // Left empty, the name typed in the create form is used instead of a random one.
        await page.fill("#playerName", "");
        await page.fill("#hostName", "Alice");
        await page.click("#joinBtn");
        await expect.poll(() => joins.length).toBe(2);
        expect(joins[1]).toEqual({playerName: "Alice"});
        expect(errors).toEqual([]);
    });
});
