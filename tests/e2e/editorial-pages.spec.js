import {test, expect} from "@playwright/test";
import {horizontalOverflow, seedSession, serveGame, syntheticGame} from "./support/synthetic.js";

const widths = [320, 390, 768, 1024];
const editorialStylesheet = 'link[rel="stylesheet"][href^="/css/editorial.css"]';
const tables = [
    {code: "LNGNM2", playerNames: ["AlexandertheGreatAndMore"], playerCount: 1, maxPlayers: 4},
    {code: "WXYZ23", playerNames: ["Maya", "Leo"], playerCount: 2, maxPlayers: 4}
];
const rules = [
    ["objective", "Objective", "The last player who still has cards loses and becomes the durak."],
    ["setup", "Deck, card order, and setup", "Every player receives six cards."],
    ["attack", "Attacking", "an attacking-side player may add only a rank that already appears on an attack or defense card"],
    ["defend", "Defending", "A trump can be beaten only by a higher trump."],
    ["transfer", "Transferring an attack", "Before covering a card, the defender may transfer the bout"],
    ["bout", "Taking cards or ending the bout", "the defender takes every card on the table"],
    ["teams", "Four-player teams", "With four players, opposite seats form teams."],
    ["online-play", "Rooms, bots, and rematches", "Player identity is stored in the current browser tab."]
];

// Keep layout and tab order independent of other tests' rooms and live invalidations.
async function mockLobbies(page) {
    await page.route("**/api/lobbies", route => route.fulfill({json: tables}));
    await page.routeWebSocket("**/ws/lobbies", () => {});
}

async function ready(page, path) {
    await page.goto(path);
    await page.evaluate(() => document.fonts.ready);
}

async function box(locator) {
    await expect(locator).toBeVisible();
    const bounds = await locator.boundingBox();
    expect(bounds).not.toBeNull();
    return bounds;
}

function center(bounds) {
    return bounds.x + bounds.width / 2;
}

function bottom(bounds) {
    return bounds.y + bounds.height;
}

async function expectNoOverflow(page) {
    const {scrollWidth, clientWidth} = await horizontalOverflow(page);
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth);
}

async function expectOnScreen(locator, width) {
    const bounds = await box(locator);
    expect(bounds.x).toBeGreaterThanOrEqual(-0.5);
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(width + 0.5);
    return bounds;
}

async function expectCenteredText(locator, expectedCenter) {
    // Measure the ink's line boxes, not a full-width heading that could hide left-aligned text.
    const bounds = await locator.evaluate(element => {
        const range = document.createRange();
        range.selectNodeContents(element);
        const rect = range.getBoundingClientRect();
        return {x: rect.x, width: rect.width};
    });
    expect(Math.abs(center(bounds) - expectedCenter)).toBeLessThanOrEqual(2);
}

async function expectFocusVisible(locator) {
    await expect(locator).toBeFocused();
    await expect(locator).toBeInViewport();
    expect(await locator.evaluate(element => {
        const style = getComputedStyle(element);
        return element.matches(":focus-visible") && (
            (style.outlineStyle !== "none" && parseFloat(style.outlineWidth) > 0) ||
            style.boxShadow !== "none"
        );
    }), "keyboard focus has a visible indicator").toBe(true);
}

function expectNoOverlap(a, b, description) {
    const overlapX = Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x);
    const overlapY = Math.min(bottom(a), bottom(b)) - Math.max(a.y, b.y);
    expect(Math.min(overlapX, overlapY), description).toBeLessThanOrEqual(0.5);
}

async function expectReadableAction(locator) {
    await expect(locator).toBeVisible();
    const {ratio, fontSize} = await locator.evaluate(element => {
        // These editorial actions have solid fills. Composite transparent action rows and
        // translucent ancestor surfaces over the browser's white canvas, rather than treating
        // a transparent button as black. Canvas also normalizes CSS color syntaxes for us.
        const canvas = document.createElement("canvas");
        canvas.width = canvas.height = 1;
        const context = canvas.getContext("2d", {willReadFrequently: true});
        context.fillStyle = "white";
        context.fillRect(0, 0, 1, 1);
        const ancestors = [];
        for (let node = element; node; node = node.parentElement) ancestors.unshift(node);
        for (const node of ancestors) {
            context.fillStyle = getComputedStyle(node).backgroundColor;
            context.fillRect(0, 0, 1, 1);
        }
        const background = context.getImageData(0, 0, 1, 1).data;
        const style = getComputedStyle(element);
        context.fillStyle = style.color;
        context.fillRect(0, 0, 1, 1);
        const foreground = context.getImageData(0, 0, 1, 1).data;
        const luminance = rgba => [0.2126, 0.7152, 0.0722].reduce((sum, weight, index) => {
            const value = rgba[index] / 255;
            return sum + weight * (value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
        }, 0);
        const light = Math.max(luminance(background), luminance(foreground));
        const dark = Math.min(luminance(background), luminance(foreground));
        return {ratio: (light + 0.05) / (dark + 0.05), fontSize: parseFloat(style.fontSize)};
    });
    expect(ratio, `${await locator.innerText()}: text/background contrast`).toBeGreaterThanOrEqual(4.5);
    expect(fontSize, "action labels remain readable without the editorial font declarations").toBeGreaterThanOrEqual(12);
}

test.describe("Editorial landing", () => {
    test.beforeEach(async ({page}) => {
        await mockLobbies(page);
    });

    // Include both sides of the specified breakpoint as well as the four device widths.
    for (const width of [...widths, 540, 541]) {
        test(`centered header and responsive controls at ${width}px`, async ({page}) => {
            await page.setViewportSize({width, height: 900});
            await ready(page, "/");
            await expect(page.locator("#lobbyGameList .lobby-list-item")).toHaveCount(tables.length);
            const lobbyCenter = center(await box(page.locator("#lobbyView")));

            const heading = page.locator("#lobbyHeading");
            await expect(heading).toHaveAccessibleName("Durak Дурак");
            await expect(heading).toHaveCSS("font-family", /(?:^|,\s*)serif$/);
            await expectCenteredText(heading, lobbyCenter);
            const suits = page.locator(".lobby-suits");
            await expect(suits).toHaveAttribute("aria-hidden", "true");
            await expect(suits.locator("span")).toHaveCount(4);
            expect((await suits.locator("span").allTextContents()).map(text =>
                text.replace(/[\s\uFE0E\uFE0F]/g, ""))).toEqual(["♠", "♥", "♦", "♣"]);
            expect(Math.abs(center(await box(suits)) - lobbyCenter)).toBeLessThanOrEqual(2);
            const gold = await heading.locator("span").evaluate(element => getComputedStyle(element).color);
            const [red, green, blue] = gold.match(/[\d.]+/g).map(Number);
            expect(red).toBeGreaterThan(green);
            expect(green).toBeGreaterThan(blue);
            for (const suit of await suits.locator("span").all()) await expect(suit).toHaveCSS("color", gold);

            await expect(page.getByRole("textbox")).toHaveCount(2);
            await expect(page.locator("#hostName")).toHaveAccessibleName("Playing as");
            await expect(page.locator("#gameCode")).toHaveAccessibleName("Room code");
            await expect(page.locator("#playerName")).toHaveCount(1);
            await expect(page.locator("#playerName")).toHaveAttribute("type", "hidden");
            await expect(page.locator("#playerName")).toBeHidden();
            expect(await page.locator("#hostName").evaluate(input => input.form === null)).toBe(true);
            expect(await page.locator("#createBtn").evaluate(button => button.form?.id)).toBe("hostForm");
            expect(await page.locator("#joinBtn").evaluate(button => button.form?.id)).toBe("joinForm");

            const name = await box(page.locator("#hostName"));
            const nameLabel = await box(page.locator('label[for="hostName"]'));
            const host = await box(page.locator("#hostForm"));
            const joinForm = await box(page.locator("#joinForm"));
            const create = await box(page.locator("#createBtn"));
            const code = await box(page.locator("#gameCode"));
            const join = await box(page.locator("#joinBtn"));
            const quick = await box(page.locator("#quickPlayBtn"));
            const panel = await box(page.locator(".lobby-room-row"));
            expect(Math.abs(center(name) - lobbyCenter)).toBeLessThanOrEqual(2);
            expect(Math.abs(center(nameLabel) - center(name))).toBeLessThanOrEqual(2);
            expect(bottom(nameLabel)).toBeLessThanOrEqual(name.y);
            expect(bottom(name)).toBeLessThan(host.y);
            expect(bottom(name)).toBeLessThan(joinForm.y);
            expect(quick.y).toBeGreaterThan(Math.max(bottom(host), bottom(joinForm)));
            expect(quick.y).toBeGreaterThanOrEqual(bottom(panel));
            expect(Math.abs(code.y - join.y)).toBeLessThanOrEqual(2);
            expect(code.x + code.width).toBeLessThanOrEqual(join.x + 2);

            if (width <= 540) {
                expect(joinForm.y).toBeGreaterThan(bottom(host));
                expect(Math.abs(create.x - code.x)).toBeLessThanOrEqual(2);
                expect(Math.abs(create.x + create.width - join.x - join.width)).toBeLessThanOrEqual(2);
            } else {
                expect(host.x + host.width).toBeLessThan(joinForm.x);
                expect(Math.abs(create.y - code.y)).toBeLessThanOrEqual(2);
                expect(Math.abs(create.height - join.height)).toBeLessThanOrEqual(2);
                expect(Math.abs(host.width - joinForm.width)).toBeLessThanOrEqual(2);
            }

            for (const selector of ["#hostName", "#gameCode", "#createBtn", "#joinBtn", "#quickPlayBtn"]) {
                const bounds = await expectOnScreen(page.locator(selector), width);
                expect(bounds.height, `${selector} touch target`).toBeGreaterThanOrEqual(44);
            }
            for (const selector of ["#hostName", "#gameCode"]) {
                expect(await page.locator(selector).evaluate(element => parseFloat(getComputedStyle(element).fontSize)),
                    `${selector} should not trigger mobile input zoom`).toBeGreaterThanOrEqual(16);
            }
            await expect(page.locator("#createBtn")).toHaveAccessibleName("Create room");
            await expect(page.locator("#joinBtn")).toHaveAccessibleName("Join");
            await expect(page.locator("#quickPlayBtn")).toHaveAccessibleName("Quick Play vs Elektronik");
            await expectNoOverflow(page);
        });
    }

    for (const width of widths) {
        test(`open tables stay flat, readable rows at ${width}px`, async ({page}) => {
            await page.setViewportSize({width, height: 900});
            await ready(page, "/");
            const rows = page.locator("#lobbyGameList .lobby-list-item");
            await expect(rows).toHaveCount(tables.length);
            let previous;
            for (const row of await rows.all()) {
                const bounds = await expectOnScreen(row, width);
                if (previous) {
                    expect(bounds.y).toBeGreaterThanOrEqual(bottom(previous) - 0.5);
                    expect(Math.abs(bounds.x - previous.x)).toBeLessThanOrEqual(1);
                    expect(Math.abs(bounds.width - previous.width)).toBeLessThanOrEqual(1);
                }
                previous = bounds;
                await expect(row).toHaveCSS("box-shadow", "none");
                await expect(row).toHaveCSS("border-radius", "0px");
                const join = await expectOnScreen(row.getByRole("button", {name: "Join", exact: true}), width);
                expect(join.height).toBeGreaterThanOrEqual(44);
                const meta = await row.locator(".lobby-list-meta").evaluate(element => {
                    const range = document.createRange();
                    range.selectNodeContents(element);
                    return {right: range.getBoundingClientRect().right, overflow: element.scrollWidth - element.clientWidth};
                });
                expect(meta.right, "long player names must not run under Join").toBeLessThanOrEqual(join.x);
                expect(meta.overflow).toBeLessThanOrEqual(0);
            }
            await expectNoOverflow(page);
        });
    }

    for (const width of [390, 1024]) {
        test(`keyboard order and both rules links work at ${width}px`, async ({page}) => {
            await page.setViewportSize({width, height: 900});
            await ready(page, "/");
            await expect(page.locator("#lobbyGameList .lobby-list-item")).toHaveCount(tables.length);
            const help = page.getByRole("link", {name: "How to play", exact: true});
            const about = page.locator("details:has(#what-is-durak)");
            const order = [help, page.locator("#hostName"), page.locator("#createBtn"),
                page.locator("#publicRoom"), page.locator("#gameCode"), page.locator("#joinBtn"),
                page.locator("#quickPlayBtn"), ...await page.locator("#lobbyGameList .lobby-list-join").all(),
                about.locator("summary")];
            for (const control of order) {
                await page.keyboard.press("Tab");
                await expectFocusVisible(control);
                if (control === order[3]) {
                    await page.keyboard.press("Space");
                    await expect(control).not.toBeChecked();
                    await page.keyboard.press("Space");
                    await expect(control).toBeChecked();
                }
            }
            await page.keyboard.press("Enter");
            await expect(about).toHaveJSProperty("open", true);
            await page.keyboard.press("Tab");
            const fullRules = page.getByRole("link", {name: "Read the complete Durak rules →"});
            await expectFocusVisible(fullRules);
            await expect(fullRules).toHaveAttribute("href", "/rules.html");
            await page.keyboard.press("Enter");
            await expect(page).toHaveURL(/\/rules\.html$/);
            await page.getByRole("link", {name: "Durak Online home", exact: true}).click();
            await expect(page.locator("#lobbyView")).toBeVisible();
            await page.keyboard.press("Tab");
            await expectFocusVisible(help);
            await expect(help).toHaveAttribute("href", "/rules.html");
            await page.keyboard.press("Enter");
            await expect(page).toHaveURL(/\/rules\.html$/);
        });
    }
});

test.describe("Editorial fallback and enlarged text regressions", () => {
    for (const width of [390, 1024]) {
        for (const path of ["/", "/rules.html"]) {
            test(`${path} actions remain readable without the editorial stylesheet at ${width}px`, async ({page}) => {
                await page.setViewportSize({width, height: 900});
                await mockLobbies(page);
                // Model cached HTML from before editorial.css was linked, but keep the current
                // app/content styles. No Git history, archived assets or external origin needed.
                await page.route(url => url.pathname === path, async route => {
                    const response = await route.fetch();
                    const html = await response.text();
                    const link = /<link\b[^>]*\bhref\s*=\s*["']\/css\/editorial\.css(?:\?[^"']*)?["'][^>]*>/gi;
                    expect(html.match(link), "the simulation must remove exactly one stylesheet link").toHaveLength(1);
                    await route.fulfill({response, body: html.replace(link, "")});
                });
                await ready(page, path);
                await expect(page.locator(editorialStylesheet)).toHaveCount(0);
                expect(await page.evaluate(() => [...document.styleSheets].some(sheet =>
                    sheet.href && new URL(sheet.href).pathname === "/css/editorial.css"))).toBe(false);

                const actions = path === "/"
                    ? [["#createBtn", "Create room"], ["#joinBtn", "Join"], ["#quickPlayBtn", "Quick Play vs Elektronik"]]
                        .map(([selector, name]) => ({locator: page.locator(selector), name}))
                    : ["Open the game", "Create a table"].map(name => ({locator: page.getByRole("link", {name, exact: true}), name}));
                for (const {locator, name} of actions) {
                    await expect(locator).toHaveAccessibleName(name);
                    await expectReadableAction(locator);
                    if (path === "/rules.html") await expect(locator).toHaveAttribute("href", "/");
                }
            });
        }
    }

    for (const width of [541, 390, 320]) {
        for (const rootFontSize of [24, 32]) {
            test(`room code stays usable at ${width}px with a ${rootFontSize}px default font`, async ({page}) => {
                await page.setViewportSize({width, height: 900});
                await mockLobbies(page);
                await ready(page, "/");
                await page.addStyleTag({content: `html { font-size: ${rootFontSize}px !important; }`});
                await expect(page.locator("html")).toHaveCSS("font-size", `${rootFontSize}px`);
                await page.evaluate(() => document.fonts.ready);
                await expect(page.locator("#lobbyGameList .lobby-list-item")).toHaveCount(tables.length);

                const code = page.locator("#gameCode");
                await code.fill("HJK234");
                await expect(code).toHaveValue("HJK234");
                const metrics = await code.evaluate(input => {
                    const style = getComputedStyle(input);
                    return {
                        fontSize: parseFloat(style.fontSize),
                        // Padding must not count as space available to read/edit the code.
                        usableWidth: input.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight)
                    };
                });
                expect(metrics.fontSize).toBeGreaterThanOrEqual(rootFontSize);
                expect(metrics.usableWidth, "room code needs at least three font-size units of editable space")
                    .toBeGreaterThanOrEqual(metrics.fontSize * 3);
                const codeBox = await expectOnScreen(code, width);
                const joinBox = await expectOnScreen(page.locator("#joinBtn"), width);
                expectNoOverlap(codeBox, joinBox, "Join must not overlap the enlarged room-code input");
                for (const selector of ["#hostName", "#createBtn", "#quickPlayBtn"]) {
                    await expectOnScreen(page.locator(selector), width);
                }
                for (const row of await page.locator("#lobbyGameList .lobby-list-item").all()) {
                    const textRight = await row.locator(".lobby-list-meta").evaluate(element => {
                        const range = document.createRange();
                        range.selectNodeContents(element);
                        return range.getBoundingClientRect().right;
                    });
                    const button = await box(row.locator(".lobby-list-join"));
                    expect(textRight, "enlarged room names and codes must not overlap Join").toBeLessThanOrEqual(button.x);
                }
                // Allow either side-by-side or stacked controls: the regression is unusable
                // input space, not which grid/container-query implementation fixes it.
                await expectNoOverflow(page);
            });
        }
    }

    test("rules header links do not overlap at 390px with a 32px default font", async ({page}) => {
        const width = 390;
        await page.setViewportSize({width, height: 900});
        await ready(page, "/rules.html");
        await page.addStyleTag({content: "html { font-size: 32px !important; }"});
        await expect(page.locator("html")).toHaveCSS("font-size", "32px");
        await page.evaluate(() => document.fonts.ready);
        const home = page.getByRole("link", {name: "Durak Online home", exact: true});
        const back = page.getByRole("navigation", {name: "Site navigation"}).getByRole("link", {name: "Back to game", exact: true});
        const homeBox = await expectOnScreen(home, width);
        const backBox = await expectOnScreen(back, width);
        expectNoOverlap(homeBox, backBox, "wordmark and return link must have separate clickable areas");
        const textBounds = [];
        for (const link of [home, back]) {
            await expect(link).toHaveAttribute("href", "/");
            const text = await link.evaluate(element => {
                const range = document.createRange();
                range.selectNodeContents(element);
                const rect = range.getBoundingClientRect();
                return {x: rect.x, y: rect.y, width: rect.width, height: rect.height};
            });
            expect(text.x).toBeGreaterThanOrEqual(-0.5);
            expect(text.x + text.width).toBeLessThanOrEqual(width + 0.5);
            textBounds.push(text);
        }
        expectNoOverlap(textBounds[0], textBounds[1], "rendered link text must not overlap even if flex boxes shrink");
        await expectNoOverflow(page);
    });
});

test.describe("Editorial rules", () => {
    for (const width of widths) {
        test(`article remains legible without overflow at ${width}px`, async ({page}) => {
            await page.setViewportSize({width, height: 900});
            await ready(page, "/rules.html");
            await expect(page.getByRole("heading", {level: 1, name: "How to play Durak"})).toBeVisible();
            const wordmark = page.getByRole("link", {name: "Durak Online home", exact: true});
            await expect(wordmark).toHaveCSS("font-family", /(?:^|,\s*)serif$/);
            await expectOnScreen(wordmark, width);
            const article = page.locator("article#quick-reference");
            await expectOnScreen(article, width);
            const paragraphs = article.locator("p");
            expect(await paragraphs.count()).toBeGreaterThan(8);
            for (const paragraph of await paragraphs.all()) {
                await expectOnScreen(paragraph, width);
                const metrics = await paragraph.evaluate(element => {
                    const style = getComputedStyle(element);
                    const range = document.createRange();
                    range.selectNodeContents(element);
                    const text = range.getBoundingClientRect();
                    return {
                        fontSize: parseFloat(style.fontSize),
                        lineHeight: parseFloat(style.lineHeight),
                        measure: element.getBoundingClientRect().width / parseFloat(style.fontSize),
                        rankGuide: element.getAttribute("aria-label") === "Card rank from low to high",
                        textLeft: text.left, textRight: text.right
                    };
                });
                // The compact rank reference may be 14px; prose remains at least 16px.
                expect(metrics.fontSize).toBeGreaterThanOrEqual(metrics.rankGuide ? 14 : 16);
                expect(metrics.lineHeight / metrics.fontSize).toBeGreaterThanOrEqual(1.5);
                expect(metrics.measure, "reading column stays comfortably bounded").toBeLessThanOrEqual(55);
                expect(metrics.textLeft).toBeGreaterThanOrEqual(0);
                expect(metrics.textRight).toBeLessThanOrEqual(width + 0.5);
            }
            await expectNoOverflow(page);
        });
    }

    for (const width of [390, 1024]) {
        test(`original sections, anchors and return links work at ${width}px`, async ({page}) => {
            await page.setViewportSize({width, height: 900});
            await ready(page, "/rules.html");
            const toc = page.getByRole("navigation", {name: "Rules table of contents"});
            await expect(toc.getByRole("link")).toHaveCount(rules.length);
            const navigation = page.getByRole("navigation", {name: "Site navigation"});
            const jump = page.getByRole("link", {name: "Jump to the rules", exact: true});
            for (const link of [page.getByRole("link", {name: "Durak Online home", exact: true}),
                navigation.getByRole("link"), page.getByRole("link", {name: "Open the game", exact: true}),
                jump, ...await toc.getByRole("link").all()]) {
                await page.keyboard.press("Tab");
                await expectFocusVisible(link);
            }
            for (const [id, heading, copy] of rules) {
                const section = page.locator(`#${id}`);
                await expect(section.getByRole("heading", {level: 2, name: heading, exact: true})).toBeVisible();
                await expect(section).toContainText(copy);
                const link = toc.locator(`a[href="#${id}"]`);
                await link.focus();
                await page.keyboard.press("Enter");
                await expect(page).toHaveURL(new RegExp(`#${id}$`));
                await expect(section.getByRole("heading", {level: 2})).toBeInViewport();
            }
            await expect(page.getByLabel("Card rank from low to high")).toHaveText("Low → 6 · 7 · 8 · 9 · 10 · J · Q · K · A → High");
            await expect(jump).toHaveAttribute("href", "#quick-reference");
            await jump.focus();
            await page.keyboard.press("Enter");
            await expect(page).toHaveURL(/#quick-reference$/);
            await expect(page.locator("#objective h2")).toBeInViewport();
            for (const name of ["Durak Online home", "Back to game", "Open the game", "Create a table"]) {
                await expect(page.getByRole("link", {name, exact: true})).toHaveAttribute("href", "/");
            }
            await page.getByRole("link", {name: "Create a table", exact: true}).click();
            await expect(page.locator("#lobbyView")).toBeVisible();
        });
    }
});

test.describe("Editorial font isolation", () => {
    for (const path of ["/", "/rules.html"]) {
        test(`${path} uses loaded local fonts and the shared stylesheet without CSP violations`, async ({page}) => {
            await mockLobbies(page);
            await page.addInitScript(() => {
                window.__editorialCspViolations = [];
                document.addEventListener("securitypolicyviolation", event => {
                    window.__editorialCspViolations.push(`${event.effectiveDirective}: ${event.blockedURI}`);
                });
            });
            const requests = [];
            const failures = [];
            const fontResponses = [];
            const relevant = request => ["font", "stylesheet"].includes(request.resourceType());
            page.on("request", request => {
                if (relevant(request)) requests.push(request.url());
            });
            page.on("requestfailed", request => {
                if (relevant(request)) failures.push(request.url());
            });
            page.on("response", response => {
                if (response.request().resourceType() === "font") fontResponses.push({url: response.url(), status: response.status()});
                if (relevant(response.request()) && !response.ok()) failures.push(`${response.status()}: ${response.url()}`);
            });
            const response = await page.goto(path);
            expect(response.headers()["content-security-policy"]).toContain("style-src 'self'");
            await expect(page.locator(editorialStylesheet)).toHaveCount(1);
            await page.evaluate(() => document.fonts.ready);

            const selectors = path === "/" ? ["#lobbyHeading", "#hostName"] : ["h1", "#objective p"];
            for (const selector of selectors) {
                const font = await page.locator(selector).evaluate(element => {
                    const family = getComputedStyle(element).fontFamily.split(",")[0].replace(/["']/g, "").trim();
                    const faces = [...document.fonts].filter(face => face.family.replace(/["']/g, "") === family);
                    return {family, loaded: faces.some(face => face.status === "loaded")};
                });
                expect(font.loaded, `${selector}: ${font.family} actually loaded (not just a fallback)`).toBe(true);
            }
            expect(fontResponses.length).toBeGreaterThan(0);
            for (const url of requests) expect(new URL(url).origin).toBe(new URL(page.url()).origin);
            expect(failures).toEqual([]);
            expect(await page.evaluate(() => window.__editorialCspViolations)).toEqual([]);
        });
    }

    for (const width of [390, 1024]) {
        test(`editorial styles do not change gameplay typography at ${width}px`, async ({page}) => {
            await page.setViewportSize({width, height: 900});
            await seedSession(page);
            await serveGame(page, syntheticGame({table: [{attackCard: "9H"}], legalMoves: {canTake: true}}));
            await ready(page, "/");
            await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(6);
            await expect(page.locator(editorialStylesheet)).toHaveCount(1);

            // Compare against the existing theme/app styles in the same browser. No OS-specific
            // font names or screenshot baselines, but global font/heading/control leaks fail.
            const selectors = ["#gameView", "#roomHeading", "#gameCodeLabel", "#statusLabel", "#mySeatTitle",
                "#seatTop1 .seat-title", "#takeBtn", "#leaveBtn", "#helpToggleBtn"];
            const typography = () => page.evaluate(selectors => Object.fromEntries(selectors.map(selector => {
                const style = getComputedStyle(document.querySelector(selector));
                return [selector, Object.fromEntries(["fontFamily", "fontSize", "fontWeight", "fontStyle",
                    "lineHeight", "letterSpacing", "textTransform"].map(property => [property, style[property]]))];
            })), selectors);
            const withEditorial = await typography();
            for (const [selector, style] of Object.entries(withEditorial)) {
                expect(style.fontFamily, `${selector} retains the game's monospace typography`).toMatch(/(?:^|,\s*)monospace$/);
            }
            try {
                await page.locator(editorialStylesheet).evaluate(link => { link.sheet.disabled = true; });
                expect(withEditorial).toEqual(await typography());
            } finally {
                await page.locator(editorialStylesheet).evaluate(link => { link.sheet.disabled = false; });
            }
            await expect(page.locator("#lobbyView")).toBeHidden();
        });
    }
});
