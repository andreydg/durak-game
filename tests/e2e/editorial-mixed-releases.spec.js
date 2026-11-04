import {test, expect} from "@playwright/test";
import {readFileSync} from "node:fs";
import {expectReadableAction} from "./support/contrast.js";
import {horizontalOverflow} from "./support/synthetic.js";

/*
 * These are the actual HTML files shipped at 21eb2c233d4f7fada580aac9896b0459eea79d06,
 * not current markup with editorial.css removed. The fixture files are byte-identical to
 * that commit's src/main/resources/static/{index,rules}.html. Their ?v= cache keys still
 * fetch the running server's CSS and modules, just as a cached page does after a deploy.
 * Only HTML is routed: create/join and all assets use the real local server.
 */
const previous = file => readFileSync(new URL(`./fixtures/release-21eb2c2/${file}`, import.meta.url));
const pages = {"/": "index.html", "/rules.html": "rules.html"};

async function servePreviousPages(page) {
    for (const [path, file] of Object.entries(pages)) {
        // Do not fetch/forward today's response headers: its CSP import-map hash does not
        // authorize the cached HTML's import map. A cached page brings its own headers.
        await page.route(url => url.pathname === path, route => route.fulfill({
            status: 200,
            contentType: "text/html; charset=utf-8",
            body: previous(file)
        }));
    }
}

function collectPageErrors(page) {
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    return errors;
}

async function ready(page, path) {
    const response = await page.goto(path);
    expect(response).not.toBeNull();
    expect(await response.body(), "serve the release HTML without rewriting it").toEqual(previous(pages[path]));
    expect(response.headers()).not.toHaveProperty("content-security-policy");
    await page.evaluate(() => document.fonts.ready);

    // These are the original stylesheet URLs, answered by the current server, not fixtures.
    const stylesheets = await page.evaluate(() => [...document.styleSheets]
        .filter(sheet => sheet.href && sheet.cssRules.length)
        .map(sheet => new URL(sheet.href).pathname));
    expect(stylesheets).toEqual(path === "/"
        ? ["/css/theme.css", "/css/app.css", "/css/content.css"]
        : ["/css/theme.css", "/css/content.css"]);
    await expect(page.locator('link[href^="/css/editorial.css"]')).toHaveCount(0);
}

async function expectNoOverflow(page) {
    const {scrollWidth, clientWidth} = await horizontalOverflow(page);
    expect(scrollWidth, "cached page must fit the viewport").toBeLessThanOrEqual(clientWidth);
}

async function expectReadableNormalAndHover(page, locator) {
    await expectReadableAction(locator);
    await locator.hover();
    await expectReadableAction(locator);
    await page.mouse.move(0, 0);
}

async function expectCachedLobby(page) {
    await expect(page.locator("#lobbyView")).toBeVisible();
    await expect(page.locator("#quickPlayBtn")).toHaveClass("btn-primary");
    await expect(page.locator(".lobby-join-field, .brand-title, .brand-suits")).toHaveCount(0);
    const suits = page.locator(".lobby-suits");
    await expect(suits).toBeVisible();
    await expect(suits).toHaveAttribute("aria-hidden", "true");
    await expect(suits).toHaveText("♠ ♥ ♦ ♣");
    await expect(suits).toHaveCSS("color", "rgb(185, 134, 58)");
    await expect(page.locator("#lobbyHeading span")).toHaveCSS("color", "rgb(185, 134, 58)");
    await expectNoOverflow(page);
}

test.describe("Editorial pages across releases", () => {
    for (const width of [390, 1024]) {
        test(`cached home with current CSS has readable actions, gold suits and working create/join at ${width}px`, async ({page, browser, baseURL}) => {
            await page.setViewportSize({width, height: 900});
            await servePreviousPages(page);
            const errors = collectPageErrors(page);
            await ready(page, "/");
            await expectCachedLobby(page);

            // These old .btn-primary actions have opaque, solid fills even though the cached
            // page retains theme.css's body gradient (no editorial.css background override).
            for (const [selector, name] of [
                ["#createBtn", "Create room"],
                ["#joinBtn", "Join"],
                ["#quickPlayBtn", "Quick Play vs Elektronik"]
            ]) {
                const action = page.locator(selector);
                await expect(action).toHaveAccessibleName(name);
                await expectReadableNormalAndHover(page, action);
            }
            await expectNoOverflow(page);

            // Use a real, private waiting room so discovery and other tests' rooms cannot
            // select the target. No bot, provider, mocked API or synthetic session is involved.
            await page.locator("#hostName").fill("Cached Host");
            await page.locator("#publicRoom").uncheck();
            await page.locator("#createBtn").click();
            await expect(page.locator("#gameView")).toBeVisible();
            await expect(page.locator("#gameCodeLabel")).toHaveText(/^[A-HJ-NP-Z2-9]{6}$/);
            const code = (await page.locator("#gameCodeLabel").textContent()).trim();
            await expect(page.locator("#statusLabel")).toHaveText("Lobby");
            await expect(page.locator("#visibilityLabel")).toHaveText("Invite only");
            await expect(page.locator("#roleLabel")).toContainText("Cached Host");

            const guestContext = await browser.newContext({baseURL, viewport: {width, height: 900}});
            try {
                const guest = await guestContext.newPage();
                const guestErrors = collectPageErrors(guest);
                await servePreviousPages(guest);
                await ready(guest, "/");
                await expectCachedLobby(guest);
                await guest.locator("#hostName").fill("Cached Guest");
                await guest.locator("#gameCode").fill(code);
                await expectNoOverflow(guest);
                await guest.locator("#joinBtn").click();
                await expect(guest.locator("#gameView")).toBeVisible();
                await expect(guest.locator("#gameCodeLabel")).toHaveText(code);
                await expect(guest.locator("#visibilityLabel")).toHaveText("Invite only");
                await expect(guest.locator("#roleLabel")).toContainText("Cached Host");
                await expect(guest.locator("#roleLabel")).toContainText("Cached Guest");
                await expect(page.locator("#roleLabel")).toContainText("Cached Guest");

                // Leave through the UI as well, returning each cached page to its lobby.
                await guest.locator("#leaveBtn").click();
                await expectCachedLobby(guest);
                expect(guestErrors).toEqual([]);
            } finally {
                await guestContext.close();
            }
            await page.locator("#leaveBtn").click();
            await expectCachedLobby(page);
            expect(errors).toEqual([]);
        });

        test(`cached rules with current CSS have readable actions and working navigation at ${width}px`, async ({page}) => {
            await page.setViewportSize({width, height: 900});
            await servePreviousPages(page);
            const errors = collectPageErrors(page);
            await ready(page, "/rules.html");
            await expect(page.getByRole("heading", {name: "How to play Durak", exact: true})).toBeVisible();
            await expect(page.locator(".content-logo span")).toHaveCSS("color", "rgb(185, 134, 58)");

            // Primary actions are opaque; contents links sit on the solid white TOC card.
            // Do not use the solid-fill contrast helper on links over the cached body gradient.
            for (const name of ["Open the game", "Create a table"]) {
                const action = page.getByRole("link", {name, exact: true});
                await expect(action).toHaveAccessibleName(name);
                await expect(action).toHaveAttribute("href", "/");
                await expectReadableNormalAndHover(page, action);
            }
            const contents = page.getByRole("navigation", {name: "Rules table of contents"}).getByRole("link");
            await expect(contents).toHaveCount(8);
            for (const link of await contents.all()) await expectReadableNormalAndHover(page, link);
            await expectNoOverflow(page);

            const jump = page.getByRole("link", {name: "Jump to the rules", exact: true});
            await expect(jump).toHaveAttribute("href", "#quick-reference");
            await jump.click();
            await expect(page).toHaveURL(/#quick-reference$/);
            await expect(page.locator("#quick-reference")).toBeInViewport();
            for (const link of await contents.all()) {
                const fragment = await link.getAttribute("href");
                await link.click();
                await expect(page).toHaveURL(new RegExp(`${fragment}$`));
                await expect(page.locator(fragment).getByRole("heading")).toBeInViewport();
                await expectNoOverflow(page);
            }

            for (const name of ["Open the game", "Create a table", "Play now", "Durak Online home"]) {
                const home = page.getByRole("link", {name, exact: true});
                await expect(home).toHaveAttribute("href", "/");
                await home.click();
                await expect(page).toHaveURL(/\/$/);
                await expectCachedLobby(page);
                await page.getByRole("link", {name: "How to play", exact: true}).click();
                await expect(page).toHaveURL(/\/rules\.html$/);
                await expect(page.getByRole("heading", {name: "How to play Durak", exact: true})).toBeVisible();
                await expectNoOverflow(page);
            }
            expect(errors).toEqual([]);
        });
    }
});
