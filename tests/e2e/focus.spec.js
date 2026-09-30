import { test, expect } from "@playwright/test";
import { seedSession, syntheticGame } from "./support/synthetic.js";

const CODE = "SYNTH2";
const HAND = ["6C", "7D", "8H", "9S", "JC", "QD"];

/** What has focus, as a short description ("card:7D", "#takeBtn", "body"). */
function focused(page) {
    return page.evaluate(() => {
        const active = document.activeElement;
        if (!active || active === document.body) return "body";
        if (active.dataset.cardCode) return `card:${active.dataset.cardCode}`;
        return active.id ? `#${active.id}` : active.tagName.toLowerCase();
    });
}

/**
 * Serves a mutable synthetic game. POSTs are answered by `onPost(path)` (which may swap the game)
 * and the realtime socket is captured so tests can push BOT_THINKING / GAME_UPDATED messages.
 */
async function table(page, initial, onPost = () => null) {
    const ctx = {game: initial, socket: null};
    await seedSession(page);
    await page.routeWebSocket("**/ws/games/**", socket => { ctx.socket = socket; });
    await page.route(`**/api/games/${CODE}**`, async route => {
        const request = route.request();
        if (request.method() === "POST") {
            const next = onPost(new URL(request.url()).pathname, ctx);
            if (next) ctx.game = next;
        }
        await route.fulfill({status: 200, contentType: "application/json", body: JSON.stringify(ctx.game)});
    });
    return ctx;
}

function attacking(overrides = {}) {
    return syntheticGame({
        version: 30,
        attackerPlayerId: "me",
        defenderPlayerId: "p2",
        hand: HAND,
        legalMoves: {canAttack: true, attackableCardCodes: HAND},
        ...overrides
    });
}

test.describe("Keyboard focus survives game updates", () => {
    test("Enter and Space on a card select it and keep focus on it", async ({ page }) => {
        await table(page, attacking());
        await page.goto("/");
        const card = page.locator('#myHand [data-card-code="8H"]');
        await card.focus();

        await page.keyboard.press("Enter");
        await expect(card).toHaveAttribute("aria-pressed", "true");
        expect(await focused(page)).toBe("card:8H");

        await page.keyboard.press("Space");
        await expect(card).toHaveAttribute("aria-pressed", "false");
        expect(await focused(page)).toBe("card:8H");
    });

    test("bot thinking and game updates do not steal focus", async ({ page }) => {
        const ctx = await table(page, attacking());
        await page.goto("/");
        await expect.poll(() => ctx.socket !== null).toBe(true);
        await page.locator('#myHand [data-card-code="9S"]').focus();
        await page.keyboard.press("Enter");

        // Snapshots carry the same thinking state as the socket, as they do on the real server.
        const thinking = {p2: "planning defence..."};
        ctx.game = {...ctx.game, botThinking: thinking};
        ctx.socket.send(JSON.stringify({type: "BOT_THINKING", playerId: "p2", thinking: true, message: "planning defence...", eventAtMs: Date.now()}));
        await expect(page.locator("#seatTop1 .bot-thinking-inline")).toBeVisible();
        expect(await focused(page)).toBe("card:9S");

        ctx.game = attacking({version: 31, table: [{attackCard: "10H", attackerId: "me"}], botThinking: thinking});
        ctx.socket.send(JSON.stringify({type: "GAME_UPDATED", version: 31}));
        await expect(page.locator("#battleCards .battle-pair")).toHaveCount(1);
        expect(await focused(page)).toBe("card:9S");
        await expect(page.locator('#myHand [data-card-code="9S"]')).toHaveAttribute("aria-pressed", "true");

        ctx.game = {...ctx.game, botThinking: {}};
        ctx.socket.send(JSON.stringify({type: "BOT_THINKING", playerId: "p2", thinking: false, eventAtMs: Date.now() + 1}));
        await expect(page.locator("#seatTop1 .bot-thinking-inline")).toHaveCount(0);
        expect(await focused(page)).toBe("card:9S");
    });

    test("attacking from the keyboard lands focus on the played card's neighbour", async ({ page }) => {
        await table(page, attacking(), path => path.endsWith("/attack")
            ? attacking({
                version: 31,
                hand: HAND.filter(card => card !== "8H"),
                table: [{attackCard: "8H", attackerId: "me"}],
                legalMoves: {}
            })
            : null);
        await page.goto("/");
        await page.locator('#myHand [data-card-code="8H"]').focus();
        await page.keyboard.press("Enter");
        await page.locator("#attackBtn").focus();

        await page.keyboard.press("Enter");

        await expect(page.locator('#myHand [data-card-code="8H"]')).toHaveCount(0);
        await expect.poll(() => focused(page)).toBe("card:9S");
    });

    test("Space on Take cards moves focus into the hand once Take is spent", async ({ page }) => {
        const defending = syntheticGame({
            version: 40,
            table: [{attackCard: "10H"}],
            hand: HAND,
            legalMoves: {canTake: true}
        });
        await table(page, defending, path => path.endsWith("/take")
            ? syntheticGame({
                version: 41,
                table: [{attackCard: "10H"}],
                hand: HAND,
                takingCardsInProgress: true,
                takingPlayerId: "me"
            })
            : null);
        await page.goto("/");
        await page.locator("#takeBtn").focus();

        await page.keyboard.press("Space");

        await expect(page.locator("#takeBtn")).toBeDisabled();
        await expect.poll(() => focused(page)).toBe("card:6C");
    });

    test("view changes move focus to the new view's heading", async ({ page }) => {
        const ctx = await table(page, attacking());
        await page.goto("/");
        await expect(page.locator("#playingArea")).toBeVisible();
        await page.locator('#myHand [data-card-code="6C"]').focus();

        ctx.game = syntheticGame({version: 50, status: "FINISHED", loserPlayerId: "p2", hand: []});
        await page.evaluate(() => window.refreshGame(false));

        await expect(page.locator("#resultPanel")).toBeVisible();
        await expect(page.locator("#resultTitle")).toBeFocused();
    });

    test("creating and leaving a room focus the room and lobby headings", async ({ page }) => {
        await page.goto("/");
        await page.fill("#hostName", "Focus Host");
        await page.locator("#createBtn").focus();
        await page.keyboard.press("Enter");

        await expect(page.locator("#gameView")).toBeVisible();
        await expect(page.locator("#roomHeading")).toBeFocused();

        await page.locator("#leaveBtn").focus();
        await page.keyboard.press("Enter");
        await expect(page.locator("#lobbyView")).toBeVisible();
        await expect(page.locator("#lobbyHeading")).toBeFocused();
    });

    test("an Open tables refresh keeps focus on the same Join button", async ({ page, browser }) => {
        await page.goto("/");
        await page.fill("#hostName", "List Host");
        await page.click("#createBtn");
        await expect(page.locator("#gameCodeLabel")).toHaveText(/^[A-Z0-9]{6}$/);
        const code = (await page.locator("#gameCodeLabel").textContent()).trim();

        const visitorContext = await browser.newContext();
        const visitor = await visitorContext.newPage();
        await visitor.goto("/");
        const join = visitor.locator(`.lobby-list-join[data-code="${code}"]`);
        await expect(join).toBeVisible({ timeout: 15_000 });
        await join.focus();

        await visitor.evaluate(() => window.refreshLobbyLists());

        await expect(visitor.locator(`.lobby-list-join[data-code="${code}"]`)).toBeFocused();
        await visitorContext.close();
    });
});
