import {test, expect} from "@playwright/test";
import {readFileSync} from "node:fs";
import {expectReadableAction} from "./support/contrast.js";
import {setDefaultFont} from "./support/editorial-layout.js";
import {gameHeaderMetrics} from "./support/game-header-layout.js";
import {horizontalOverflow, seedSession, serveGame, syntheticGame} from "./support/synthetic.js";

async function openGame(page, game, width = 1024, height = 900) {
    await page.setViewportSize({width, height});
    await seedSession(page);
    await serveGame(page, game);
    await page.route("**/api/lobbies", route => route.fulfill({json: []}));
    await page.routeWebSocket("**/ws/lobbies", () => {});
    await page.goto("/");
    await expect(page.locator("#gameView")).toBeVisible();
    await page.evaluate(() => document.fonts.ready);
}

test.describe("Editorial game presentation", () => {
    const headerStates = [
        ["two-player", {players: 2}],
        ["four-player", {players: 4}],
        ["host waiting", {status: "LOBBY"}],
        ["guest waiting", {status: "LOBBY", hostPlayerId: "p2"}],
        ["finished", {status: "FINISHED", loserPlayerId: "p2", hand: []}]
    ];
    for (const font of [20, 24]) {
        for (const [state, options] of headerStates) {
            const widths = [320, 375, 390, 541, 700, 768, 1024];
            if (state === "host waiting") widths.push(710, 770, 840);
            for (const width of widths) {
                test(`header wraps without collision: ${state}, ${font}px text at ${width}px`, async ({page}) => {
                    // Must precede navigation; full-page screenshots reset this preference.
                    await setDefaultFont(page, font);
                    const game = syntheticGame(options);
                    // The busiest host header includes Add bot as well as Start and Copy invite.
                    if (game.status === "LOBBY") game.players[1].bot = false;
                    await openGame(page, game, width);
                    if (state === "host waiting") await expect(page.locator("#addBotBtn")).toBeVisible();
                    const metrics = await gameHeaderMetrics(page);
                    expect(metrics.overlap).toBe(0);
                    expect(metrics.summaryOverlap).toBe(0);
                    expect(metrics.actionsOnScreen).toBe(true);
                    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth);
                });
            }
        }
    }

    for (const width of [320, 390, 1024]) {
        for (const players of [2, 3, 4]) {
            test(`${players} seats at ${width}px keep the existing information and controls`, async ({page}) => {
                await openGame(page, syntheticGame({players, opponentHandSizes: [14, 3, 6]}), width);
                await expect(page.locator(".game-brand .brand-title")).toHaveText("Durak Дурак");
                await expect(page.locator("#gameView")).toHaveCSS("font-family", /DM Sans/);
                await expect(page.locator("#takeBtn")).toHaveCSS("font-family", /DM Sans/);
                await expect(page.locator("#tableGrid")).toHaveClass(`table-grid players-${players}`);
                await expect(page.locator(".seat.top:visible")).toHaveCount(players - 1);
                await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(6);
                // Exact opponent counts must not be added to either names or fans.
                await expect(page.locator("#seatTop1 .card-back-face")).toHaveCount(6);
                await expect(page.locator("#seatTop1 .back-fan")).toHaveAttribute("aria-label", "6 or more cards");
                for (const seat of await page.locator(".seat.top:visible").all()) {
                    expect(await seat.innerText()).not.toMatch(/\b\d+\s+cards?\b/i);
                }
                expect(await page.locator("#mySeatTitle").innerText()).not.toMatch(/\b\d+\s+cards?\b/i);
                if (players >= 3) {
                    // A fan under six still shows its existing number of backs.
                    const shortFan = page.locator('.back-fan[aria-label="3 cards"]');
                    await expect(shortFan).toHaveCount(1);
                    await expect(shortFan.locator(".card-back-face")).toHaveCount(3);
                }
                await expect(page.locator("#deckArea")).toHaveText("");
                const suit = page.locator("#trumpSuitHud");
                await expect(suit.locator(":scope > :not(.visually-hidden)")).toHaveText("♠");
                await expect(suit).not.toHaveAttribute("title", /.+/);
                await suit.hover();
                await expect(suit.locator("[title]")).toHaveCount(0);
                // Keep screen-reader descriptions while removing visible/hover wording.
                await expect(suit.locator(".visually-hidden")).toHaveText("Trump: spades");
                const suitBox = await suit.boundingBox();
                const tableBox = await page.locator("#playingArea").boundingBox();
                expect(tableBox.x + tableBox.width - suitBox.x - suitBox.width).toBeLessThanOrEqual(25);
                expect(await suit.evaluate(el => parseFloat(getComputedStyle(el.firstElementChild).fontSize))).toBeGreaterThanOrEqual(24);
                await expect(page.locator(".action-strip button")).toHaveText(["Attack", "Defend", "Transfer", "Take cards", "End round"]);
                await expect(page.locator("#startBtn, #addBotBtn, #shareBtn").filter({visible: true})).toHaveCount(0);
                await expect(page.locator("#leaveBtn")).toBeVisible();
                await page.locator("#roomHeading").focus();
                await page.keyboard.press("Tab");
                await expect(page.locator("#copyCodeBtn")).toBeFocused();
                await page.keyboard.press("Tab");
                await expect(page.locator(".game-rules-link")).toBeFocused();
                await page.keyboard.press("Tab");
                await expect(page.locator("#leaveBtn")).toBeFocused();
                const {scrollWidth, clientWidth} = await horizontalOverflow(page);
                expect(scrollWidth).toBeLessThanOrEqual(clientWidth);
            });
        }
    }

    for (const [width, height, players] of [[1024, 768, 2], [1440, 789, 2], [1280, 800, 3], [375, 667, 3], [375, 667, 4]]) {
        test(`approved two-row header at ${width}x${height} with ${players} players`, async ({page}) => {
            await openGame(page, syntheticGame({players}), width, height);
            const metrics = await gameHeaderMetrics(page);
            expect(metrics.overlap).toBe(0);
            expect(metrics.summaryOverlap).toBe(0);
            expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth);
            const brand = await page.locator(".game-brand").boundingBox();
            const actions = await page.locator(".room-actions").boundingBox();
            const summary = await page.locator(".room-summary").boundingBox();
            expect(summary.y).toBeGreaterThanOrEqual(Math.max(brand.y + brand.height, actions.y + actions.height));
            await expect(page.locator(".room-summary")).toHaveCSS("border-top-width", "1px");
            if (width > 700) {
                await expect(page.locator(".game-brand .brand-suits")).toBeVisible();
                await expect(page.locator(".game-brand .brand-suits")).toHaveCSS("color", "rgb(185, 134, 58)");
                await expect(page.locator(".game-brand .brand-suits")).toHaveCSS("opacity", "1");
                const fontSize = await page.locator(".game-brand .brand-title").evaluate(el => parseFloat(getComputedStyle(el).fontSize));
                expect(fontSize).toBe(32);
                const names = await page.locator("#roleLabel").boundingBox();
                expect(Math.abs(names.x + names.width - summary.x - summary.width)).toBeLessThanOrEqual(1);
            }
            await expect(page.locator("#roleLabel")).toContainText("(host)");
            // The taller approved masthead may require scrolling on short screens. Do not
            // collapse the design to make an arbitrary initial-fold height assertion pass.
            await page.locator(".action-strip").scrollIntoViewIfNeeded();
            await expect(page.locator(".action-strip")).toBeInViewport();
        });
    }

    test("taking, team roles and bot thinking retain their full meaning", async ({page}) => {
        await openGame(page, syntheticGame({
            players: 4, attackerPlayerId: "me", defenderPlayerId: "p2",
            takingCardsInProgress: true, takingPlayerId: "p2",
            table: [{attackCard: "9H"}], botThinking: {p2: "planning defence..."},
            legalMoves: {canEndRound: true}
        }));
        await expect(page.locator("#battleTableBanner")).toHaveText("Krzysztof Elektronik is taking cards. Throw in matching ranks (first come), then all attackers press End round.");
        await expect(page.locator("#seatTop1 .seat-role-inline")).toContainText("team 1");
        await expect(page.locator("#seatTop1 .seat-role-inline svg")).toHaveCount(2);
        await expect(page.locator("#seatTop1 .bot-thinking-inline")).toHaveText("planning defence");
        await expect(page.locator("#mySeatTitle")).toContainText(", attacker, team 0");
        await expect(page.locator("#endRoundBtn")).toBeEnabled();
        await expectReadableAction(page.locator("#endRoundBtn"));
        await expectReadableAction(page.locator("#mySeatTitle"));
        await expect(page.locator("#seatTop1 .ai-badge")).toHaveAttribute("title", "Bot");
        await page.locator("#helpToggleBtn").click();
        await expect(page.locator("#gameplayHint")).toBeVisible();
        await expect(page.locator("#helpToggleBtn")).toHaveText("Hide help");
    });

    for (const width of [320, 1024]) {
        test(`the defence shield stays readable beside names at ${width}px`, async ({page}) => {
            await openGame(page, syntheticGame(), width);
            for (const selector of ["#mySeatTitle", ".hud"]) {
                const shield = page.locator(`${selector} .game-icon--defender`);
                await expect(shield).toBeVisible();
                await expect(shield).toHaveAttribute("aria-hidden", "true");
                await expect(shield).toHaveCSS("stroke-width", "2.2px");
                const bounds = await shield.boundingBox();
                expect(bounds.width).toBeGreaterThanOrEqual(20);
                expect(bounds.height).toBeGreaterThanOrEqual(20);
            }
            await expect(page.locator("#mySeatTitle")).toContainText(", defender");
        });

        test(`How to play opens beside Leave without navigating away from the game at ${width}px`, async ({page, context}) => {
            await openGame(page, syntheticGame(), width);
            const link = page.getByRole("link", {name: "How to play (opens in a new tab)"});
            await expect(link).toHaveAttribute("rel", "noopener");
            await expectReadableAction(link);
            const linkBox = await link.boundingBox();
            const leaveBox = await page.locator("#leaveBtn").boundingBox();
            expect(linkBox.x + linkBox.width).toBeLessThanOrEqual(leaveBox.x);
            expect(Math.abs(linkBox.y - leaveBox.y)).toBeLessThanOrEqual(1);
            expect((await gameHeaderMetrics(page)).overlap).toBe(0);
            const url = page.url();
            const originalSession = await page.evaluate(() => JSON.stringify(sessionStorage));
            const newPage = context.waitForEvent("page");
            await link.click();
            const rules = await newPage;
            await rules.waitForLoadState();
            await expect(rules).toHaveURL(/\/rules\.html$/);
            await expect(rules.getByRole("heading", {name: "How to play Durak", exact: true})).toBeVisible();
            await expect(page).toHaveURL(url);
            await expect(page.locator("#playingArea")).toBeVisible();
            expect(await page.evaluate(() => JSON.stringify(sessionStorage))).toBe(originalSession);
            await rules.close();
        });
    }

    test("empty talon keeps the suit but removes the pile, without new labels", async ({page}) => {
        await openGame(page, syntheticGame({talonSize: 0}), 390);
        await expect(page.locator("#deckArea")).toBeHidden();
        await expect(page.locator("#trumpSuitHud")).toBeVisible();
        await expect(page.locator("#trumpSuitHud > :not(.visually-hidden)")).toHaveText("♠");
        await expect(page.locator("#trumpSuitHud")).not.toHaveAttribute("title", /.+/);
    });

    for (const publicRoom of [true, false]) {
        for (const host of [true, false]) {
            test(`${publicRoom ? "public" : "private"} waiting room keeps ${host ? "host" : "guest"} controls`, async ({page}) => {
                const game = syntheticGame({status: "LOBBY", publicRoom, hostPlayerId: host ? "me" : "p2"});
                game.players[1].bot = false;
                await openGame(page, game, 390);
                await expect(page.locator("#playingArea, #resultPanel").filter({visible: true})).toHaveCount(0);
                await expect(page.locator("#visibilityLabel")).toHaveText(publicRoom ? "Public room" : "Invite only");
                await expect(page.locator("#shareBtn")).toBeVisible();
                await expect(page.locator("#roomWaitingLine")).toContainText(publicRoom ? "Open tables" : "invite-only");
                await expect(page.locator("#gameOpenTablesWrap")).toBeVisible({visible: publicRoom});
                await expect(page.locator("#startBtn")).toBeVisible({visible: host});
                await expect(page.locator("#addBotBtn")).toBeVisible({visible: host});
                await expect(page.locator("#leaveBtn")).toBeVisible();
                await page.locator("#roomHeading").focus();
                await page.keyboard.press("Tab");
                await expect(page.locator("#copyCodeBtn")).toBeFocused();
                await page.keyboard.press("Tab");
                await expect(page.locator(host ? "#startBtn" : "#shareBtn")).toBeFocused();
                const {scrollWidth, clientWidth} = await horizontalOverflow(page);
                expect(scrollWidth).toBeLessThanOrEqual(clientWidth);
            });
        }
    }

    for (const loserPlayerId of ["me", "p2", null]) {
        test(`finished ${loserPlayerId || "draw"} preserves the result and rematch control`, async ({page}) => {
            await openGame(page, syntheticGame({status: "FINISHED", loserPlayerId, hand: []}), 390);
            await expect(page.locator("#playingArea")).toBeHidden();
            await expect(page.locator("#resultPanel")).toBeVisible();
            await expect(page.locator("#resultPanel")).toHaveAttribute("data-outcome", loserPlayerId === "me" ? "loss" : loserPlayerId === "p2" ? "win" : "draw");
            await expect(page.locator("#resultTitle")).toHaveCSS("font-family", /DM Serif Display/);
            await expect(page.locator("#rematchBtn")).toBeVisible();
            await expectReadableAction(page.locator("#rematchBtn"));
        });
    }

    for (const publicRoom of [true, false]) {
        test(`header details match the mock without changing ${publicRoom ? "public" : "private"} room meaning`, async ({page}) => {
            await openGame(page, syntheticGame({publicRoom}), 1440);
            await expect(page.locator("#roomHeading")).toHaveAccessibleName("Game SYNTH2");
            await expect(page.locator("#roomHeading > :not(.visually-hidden)")).toHaveText("SYNTH2");
            await expect(page.locator("#roomHeading .visually-hidden")).toHaveCSS("clip-path", "inset(50%)");
            await expect(page.getByRole("button", {name: "Copy room code"})).toBeVisible();
            await expect(page.locator("#visibilityLabel")).toHaveText(publicRoom ? "Public room" : "Invite only");
            await expect(page.locator("#visibilityLabel svg")).toHaveCount(publicRoom ? 0 : 1);
            if (!publicRoom) await expect(page.locator("#visibilityLabel svg")).toHaveAttribute("aria-hidden", "true");
            await expect(page.locator("#leaveBtn")).toHaveAccessibleName("Leave");
            await expect(page.locator("#leaveBtn svg")).toBeVisible();
            expect((await page.locator("#roleLabel > :not(.visually-hidden)").allTextContents()).join("")).not.toContain("Players:");
            await expect(page.locator("#roleLabel")).toContainText("Players:");
            await expect(page.locator(".room-player-separator")).toHaveText("·");
            await expectReadableAction(page.locator("#copyCodeBtn"));
        });
    }

    test("copy code works by keyboard, copies only the code, and leaves the seat unchanged", async ({page}) => {
        await page.addInitScript(() => Object.defineProperty(navigator, "clipboard", {
            value: {writeText: async text => { window.copiedText = text; }}
        }));
        await openGame(page, syntheticGame());
        const session = await page.evaluate(() => JSON.stringify(sessionStorage));
        await page.locator("#roomHeading").focus();
        await page.keyboard.press("Tab");
        await page.keyboard.press("Enter");
        await expect.poll(() => page.evaluate(() => window.copiedText)).toBe("SYNTH2");
        await expect(page.locator("#liveAnnouncer")).toContainText("Room code copied.");
        await expect(page.locator("#copyCodeBtn")).toHaveAttribute("data-copied", "true");
        await expect(page.locator("#copyCodeBtn")).toBeFocused();
        expect(await page.evaluate(() => JSON.stringify(sessionStorage))).toBe(session);
        await expect(page.locator("#copyCodeBtn")).not.toHaveAttribute("data-copied", "true");
        await expect(page.locator("#copyCodeBtn")).toHaveAttribute("title", "Copy room code");
    });

    for (const success of [true, false]) {
        test(`legacy clipboard ${success ? "success" : "failure"} removes the temporary field and keeps focus`, async ({page}) => {
            await page.addInitScript(success => {
                Object.defineProperty(navigator, "clipboard", {value: undefined});
                document.execCommand = command => {
                    window.copyAttempt = {command, text: document.activeElement.value};
                    return success;
                };
            }, success);
            await openGame(page, syntheticGame());
            await page.getByRole("button", {name: "Copy room code"}).click();
            expect(await page.evaluate(() => window.copyAttempt)).toEqual({command: "copy", text: "SYNTH2"});
            await expect(page.locator("textarea")).toHaveCount(0);
            await expect(page.locator("#copyCodeBtn")).toBeFocused();
            if (success) await expect(page.locator("#liveAnnouncer")).toContainText("Room code copied.");
            else await expect(page.locator("#appAlert")).toContainText("Select and copy the room code");
        });
    }

    test("clipboard rejection reports failure without claiming a copy", async ({page}) => {
        await page.addInitScript(() => Object.defineProperty(navigator, "clipboard", {
            value: {writeText: async () => { throw new Error("Permission denied"); }}
        }));
        await openGame(page, syntheticGame());
        await page.getByRole("button", {name: "Copy room code"}).click();
        await expect(page.locator("#appAlert")).toContainText("Could not copy");
        await expect(page.locator("#copyCodeBtn")).not.toHaveAttribute("data-copied", "true");
        await expect(page.locator("#liveAnnouncer")).not.toContainText("Room code copied.");
    });

    test("player names in the editorial roster remain literal text", async ({page}) => {
        const name = '<img src=x onerror="window.injected=true">';
        await openGame(page, syntheticGame({names: [name, "Bot & <friend>"]}));
        await expect(page.locator("#roleLabel")).toContainText(`${name} (host)`);
        await expect(page.locator("#roleLabel img")).toHaveCount(0);
        expect(await page.evaluate(() => window.injected)).toBeUndefined();
    });

    test("current HTML with older entry modules does not offer an unwired copy-code button", async ({page}) => {
        for (const file of ["main.js", "actions.js", "dom.js"]) {
            const body = readFileSync(new URL(`./fixtures/release-0b96163/${file}`, import.meta.url));
            await page.route(url => url.pathname === `/js/${file}`, route => route.fulfill({contentType: "text/javascript", body}));
        }
        const errors = [];
        page.on("pageerror", error => errors.push(error.message));
        await openGame(page, syntheticGame());
        await expect(page.locator("#copyCodeBtn")).toBeHidden();
        await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(6);
        expect(errors).toEqual([]);
    });

    test("current icons retain explicit dimensions when an older page lacks the game stylesheet", async ({page}) => {
        const previous = readFileSync(new URL("./fixtures/release-21eb2c2/index.html", import.meta.url));
        await page.route(url => url.pathname === "/", route => route.fulfill({contentType: "text/html", body: previous}));
        await openGame(page, syntheticGame({players: 4, takingCardsInProgress: true, takingPlayerId: "p2", defenderPlayerId: "p2"}), 390);
        await expect(page.locator('link[href^="/css/game-editorial.css"]')).toHaveCount(0);
        const icons = page.locator("#gameView .game-icon");
        expect(await icons.count()).toBeGreaterThan(0);
        for (const icon of await icons.all()) {
            await expect(icon).toHaveAttribute("aria-hidden", "true");
            await expect(icon).toHaveAttribute("width", "16");
            await expect(icon).toHaveAttribute("height", "16");
            const box = await icon.boundingBox();
            if (box) { expect(box.width).toBe(16); expect(box.height).toBe(16); }
        }
        const {scrollWidth, clientWidth} = await horizontalOverflow(page);
        expect(scrollWidth).toBeLessThanOrEqual(clientWidth);
    });

    for (const touch of [false, true]) {
        test.describe(`stylesheet fallback with ${touch ? "touch" : "mouse"}`, () => {
            test.use({hasTouch: touch, isMobile: touch});
            for (const [width, players] of [[390, 2], [1024, 2], [320, 4], [390, 4]]) {
                test(`missing companion stylesheet keeps a usable header: ${players} players at ${width}px`, async ({page}) => {
                    await page.route("**/css/game-editorial.css*", route => route.fulfill({status: 404, body: ""}));
                    await openGame(page, syntheticGame({players}), width);
                    const metrics = await gameHeaderMetrics(page);
                    // A zero-width overflowing grid item can look hidden to Playwright while its text paints.
                    expect.soft(metrics.brandDisplay).toBe("none");
                    if (players === 2) expect.soft(metrics.headerHeight).toBeLessThanOrEqual(90);
                    expect(metrics.summaryOverlap).toBe(0);
                    expect(metrics.actionsOnScreen).toBe(true);
                    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth);
                });
            }
        });
    }
});
