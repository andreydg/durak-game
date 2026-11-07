import {test, expect} from "@playwright/test";
import {readFileSync} from "node:fs";
import {seedSession, serveGame, syntheticGame} from "./support/synthetic.js";

async function openGame(page, options = {}, width = 1024, height = 900) {
    await page.setViewportSize({width, height});
    await seedSession(page);
    await serveGame(page, syntheticGame(options));
    await page.route("**/api/lobbies", route => route.fulfill({json: []}));
    await page.routeWebSocket("**/ws/lobbies", () => {});
    await page.goto("/");
    await expect(page.locator("#gameView")).toBeVisible();
    await page.evaluate(() => document.fonts.ready);
}

test.describe("R1: copy touch target without a larger layout box", () => {
    test.use({hasTouch: true, isMobile: true});
    for (const missing of [false, true]) {
        test(`44px hit area with companion stylesheet ${missing ? "missing" : "present"}`, async ({page}) => {
            if (missing) await page.route("**/css/game-editorial.css*", route => route.fulfill({status: 404, body: ""}));
            await openGame(page, {status: "LOBBY", publicRoom: false}, 390);
            expect(await page.evaluate(() => matchMedia("(pointer: coarse)").matches)).toBe(true);
            const button = page.locator("#copyCodeBtn");
            const box = await button.boundingBox();
            expect(box.width).toBe(28);
            expect(box.height).toBe(28);
            const hits = await button.evaluate(button => {
                const r = button.getBoundingClientRect();
                const points = [[r.left - 6, (r.top + r.bottom) / 2], [r.right + 6, (r.top + r.bottom) / 2],
                    [(r.left + r.right) / 2, r.top - 6], [(r.left + r.right) / 2, r.bottom + 6]];
                return points.map(([x, y]) => document.elementFromPoint(x, y)?.closest("button") === button);
            });
            expect(hits).toEqual([true, true, true, true]);
            for (const selector of ["#statusLabel", "#visibilityLabel", ".game-rules-link", "#leaveBtn"]) {
                expect(await page.locator(selector).evaluate(node => {
                    // Noninteractive pills include padding; protect their actual label ink.
                    // Links/buttons keep their entire interactive box clear.
                    const range = document.createRange();
                    range.selectNodeContents(node);
                    const r = node.matches("a, button") ? node.getBoundingClientRect() : range.getBoundingClientRect();
                    return [r.left + 1, (r.left + r.right) / 2, r.right - 1].every(x =>
                        document.elementFromPoint(x, (r.top + r.bottom) / 2)?.closest("#copyCodeBtn") === null);
                }), `${selector} must not be covered by the copy target`).toBe(true);
            }
        });
    }
});

test.describe("R2: scoped copy errors and session lifetime", () => {
    test("a successful retry clears only the copy failure", async ({page}) => {
        await page.addInitScript(() => Object.defineProperty(navigator, "clipboard", {value: {
            writeText: async () => {
                if (!window.copyAttempted) { window.copyAttempted = true; throw new DOMException("Denied", "NotAllowedError"); }
            }
        }}));
        await openGame(page);
        await page.locator("#copyCodeBtn").click();
        await expect(page.locator("#appAlert")).toContainText("Could not copy");
        await page.locator("#copyCodeBtn").click();
        await expect(page.locator("#copyCodeBtn")).toHaveAttribute("data-copied", "true");
        await expect(page.locator("#appAlert")).toBeHidden();
    });

    test("an unrelated action error survives a successful copy", async ({page}) => {
        await page.addInitScript(() => Object.defineProperty(navigator, "clipboard", {value: {writeText: async () => {}}}));
        await openGame(page);
        await page.evaluate(async () => (await import("/js/view.js")).showError("The move failed.", "action"));
        await page.locator("#copyCodeBtn").click();
        await expect(page.locator("#copyCodeBtn")).toHaveAttribute("data-copied", "true");
        await expect(page.locator("#appAlert")).toHaveText("The move failed.");
    });

    for (const reject of [false, true]) {
        test(`leaving before clipboard ${reject ? "rejection" : "resolution"} produces no stale feedback`, async ({page}) => {
            await page.addInitScript(() => Object.defineProperty(navigator, "clipboard", {value: {
                writeText: () => new Promise((resolve, reject) => { window.finishCopy = resolve; window.rejectCopy = reject; })
            }}));
            await openGame(page, {status: "LOBBY"});
            await page.locator("#copyCodeBtn").click();
            await expect.poll(() => page.evaluate(() => typeof window.finishCopy)).toBe("function");
            await page.locator("#leaveBtn").click();
            await expect(page.locator("#lobbyView")).toBeVisible();
            await page.evaluate(reject => reject ? window.rejectCopy(new Error("Denied")) : window.finishCopy(), reject);
            // Drain the promise continuation and the following rendering task before negative assertions.
            await page.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)));
            await expect(page.locator("#liveAnnouncer")).not.toContainText("Room code copied.");
            await expect(page.locator("#copyCodeBtn")).not.toHaveAttribute("data-copied", "true");
            await expect(page.locator("#appAlert")).toBeHidden();
        });
    }
});

test.describe("R3: copy confirmation is not colour-only", () => {
    for (const missing of [false, true]) {
        test(`copy glyph becomes a check and resets, stylesheet ${missing ? "missing" : "present"}`, async ({page}) => {
            if (missing) await page.route("**/css/game-editorial.css*", route => route.fulfill({status: 404, body: ""}));
            await page.addInitScript(() => Object.defineProperty(navigator, "clipboard", {value: {writeText: async () => {}}}));
            await openGame(page);
            const button = page.locator("#copyCodeBtn");
            const copy = button.locator(".room-copy-glyph"), check = button.locator(".room-copy-check");
            const before = await button.boundingBox();
            for (const icon of [copy, check]) {
                await expect(icon).toHaveAttribute("aria-hidden", "true");
                await expect(icon).toHaveAttribute("focusable", "false");
            }
            await expect(copy).toBeVisible();
            await expect(check).toBeHidden();
            await button.click();
            await expect(check).toBeVisible();
            await expect(copy).toBeHidden();
            await expect(button).toHaveAccessibleName("Copy room code");
            expect(await button.boundingBox()).toEqual(before);
            await expect(button).not.toHaveAttribute("data-copied", "true");
            await expect(copy).toBeVisible();
            await expect(check).toBeHidden();
            await expect(button).toHaveAccessibleName("Copy room code");
        });
    }
});

test.describe("R4: fallback spacing uses text ink", () => {
    for (const oldHtml of [false, true]) {
        test(`roster and lock retain spacing with ${oldHtml ? "pre-editorial HTML" : "a missing stylesheet"}`, async ({page}) => {
            if (oldHtml) {
                const body = readFileSync(new URL("./fixtures/release-21eb2c2/index.html", import.meta.url));
                // This old page has its own import map; serve without the new page's CSP hash.
                await page.route(url => url.pathname === "/", route => route.fulfill({contentType: "text/html", body}));
            } else await page.route("**/css/game-editorial.css*", route => route.fulfill({status: 404, body: ""}));
            await openGame(page, {publicRoom: false}, 1440);
            const gaps = await page.evaluate(() => {
                const textRect = node => { const range = document.createRange(); range.selectNodeContents(node); return range.getBoundingClientRect(); };
                const separator = document.querySelector(".room-player-separator");
                const dot = textRect(separator.firstChild);
                const before = textRect(separator.previousElementSibling);
                let next = separator.nextElementSibling;
                while (next.classList.contains("visually-hidden")) next = next.nextElementSibling;
                const after = textRect(next);
                const visibility = document.querySelector("#visibilityLabel");
                const lock = visibility.querySelector("svg").getBoundingClientRect();
                const label = [...visibility.childNodes].find(n => n.nodeType === Node.TEXT_NODE && n.textContent.includes("Invite only"));
                return {before: dot.left - before.right, after: after.left - dot.right, lock: textRect(label).left - lock.right};
            });
            expect(gaps.before).toBeGreaterThanOrEqual(4);
            expect(gaps.after).toBeGreaterThanOrEqual(4);
            expect(gaps.lock).toBeGreaterThanOrEqual(3);
        });
    }
});

for (const touch of [false, true]) {
    test.describe(`R5: narrow nav with ${touch ? "touch" : "mouse"}`, () => {
        test.use({hasTouch: touch, isMobile: touch});
        test("320px keeps actions on the wordmark row without shrinking the design", async ({page}) => {
            await openGame(page, {}, 320, 568);
            const brand = await page.locator(".game-brand").boundingBox();
            const actions = await page.locator(".room-actions").boundingBox();
            const summary = await page.locator(".room-summary").boundingBox();
            expect(Math.abs(brand.y + brand.height / 2 - actions.y - actions.height / 2)).toBeLessThanOrEqual(3);
            expect(actions.y + actions.height).toBeLessThanOrEqual(summary.y);
            expect((await page.locator(".room-header").boundingBox()).height).toBeLessThanOrEqual(131);
            for (const selector of [".game-rules-link", "#leaveBtn"]) {
                const box = await page.locator(selector).boundingBox();
                expect(box.x).toBeGreaterThanOrEqual(0);
                expect(box.x + box.width).toBeLessThanOrEqual(320);
                expect(box.y + box.height).toBeLessThanOrEqual(568);
                await expect(page.locator(`${selector} svg`)).toBeHidden();
            }
            await expect(page.locator("#leaveBtn")).toHaveAccessibleName("Leave");
        });
    });
}

test.describe("R6: approved wordmark sizes", () => {
    for (const [width, size] of [[768, 28], [820, 32], [1024, 32], [1440, 32]]) {
        test(`${width}px has a ${size}px wordmark`, async ({page}) => {
            await openGame(page, {}, width);
            await expect(page.locator(".game-brand .brand-title")).toHaveCSS("font-size", `${size}px`);
        });
    }
});

test("R7: divider, suits, separator and glyphs retain the mock's detailing", async ({page}) => {
    await openGame(page, {}, 1440);
    await expect(page.locator(".room-header")).toHaveCSS("border-top-color", "rgb(223, 221, 218)");
    await expect(page.locator(".room-summary")).toHaveCSS("border-top-color", "rgb(236, 234, 231)");
    await expect(page.locator(".room-player-separator")).toHaveCSS("color", "rgb(170, 162, 152)");
    await expect(page.locator(".room-player-separator")).toHaveAttribute("aria-hidden", "true");
    await expect(page.locator(".game-brand .brand-suits")).toHaveCSS("color", "rgb(185, 134, 58)");
    await expect(page.locator(".game-brand .brand-suits")).toHaveCSS("opacity", "1");
    for (const selector of ["#copyCodeBtn .room-copy-glyph", "#leaveBtn svg"]) {
        const box = await page.locator(selector).boundingBox();
        expect(box.width).toBe(16); expect(box.height).toBe(16);
    }
    expect((await page.locator("#leaveBtn").boundingBox()).height).toBe(38);
});

test.describe("R8: keyboard cards clear the sticky header", () => {
    for (const [width, height] of [[844, 390], [1024, 600]]) {
        test(`Shift+Tab keeps every rank and suit visible at ${width}x${height}`, async ({page}) => {
            await openGame(page, {table: [{attackCard: "9H"}]}, width, height);
            await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
            await page.locator("#myHand .hand-card-btn").last().focus();
            for (let i = 0; i < 6; i++) {
                if (i) await page.keyboard.press("Shift+Tab");
                const metrics = await page.evaluate(() => ({
                    card: document.activeElement.getBoundingClientRect().top,
                    header: document.querySelector(".room-header").getBoundingClientRect().bottom,
                    isCard: document.activeElement.classList.contains("hand-card-btn")
                }));
                expect(metrics.isCard).toBe(true);
                expect(metrics.card).toBeGreaterThanOrEqual(metrics.header);
            }
            await page.locator(".action-strip").scrollIntoViewIfNeeded();
            await expect(page.locator(".action-strip")).toBeInViewport();
        });
    }
});
