import {test, expect} from "@playwright/test";
import {readFileSync} from "node:fs";
import {expectReadableAction} from "./support/contrast.js";
import {horizontalOverflow, seedSession, serveGame, syntheticGame} from "./support/synthetic.js";

async function openGame(page, game, width = 1024) {
    await page.setViewportSize({width, height: 900});
    await seedSession(page);
    await serveGame(page, game);
    await page.route("**/api/lobbies", route => route.fulfill({json: []}));
    await page.routeWebSocket("**/ws/lobbies", () => {});
    await page.goto("/");
    await expect(page.locator("#gameView")).toBeVisible();
    await page.evaluate(() => document.fonts.ready);
}

test.describe("Editorial game presentation", () => {
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
                await expect(page.locator(".game-rules-link")).toBeFocused();
                await page.keyboard.press("Tab");
                await expect(page.locator("#leaveBtn")).toBeFocused();
                const {scrollWidth, clientWidth} = await horizontalOverflow(page);
                expect(scrollWidth).toBeLessThanOrEqual(clientWidth);
            });
        }
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
            const brandBox = await page.locator(".game-brand").boundingBox();
            expect(linkBox.x + linkBox.width).toBeLessThanOrEqual(leaveBox.x);
            expect(Math.abs(linkBox.y - leaveBox.y)).toBeLessThanOrEqual(1);
            expect(brandBox.x + brandBox.width).toBeLessThanOrEqual(linkBox.x);
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
});
