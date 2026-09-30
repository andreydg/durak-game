import { test, expect } from "@playwright/test";
import { seedSession, syntheticGame } from "./support/synthetic.js";

const CODE = "SYNTH2";

async function table(page, initial) {
    const ctx = {game: initial, socket: null};
    await seedSession(page);
    await page.routeWebSocket("**/ws/games/**", socket => { ctx.socket = socket; });
    await page.route(`**/api/games/${CODE}**`, route => route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(ctx.game)
    }));
    return ctx;
}

function defendingThree(overrides = {}) {
    return syntheticGame({
        version: 10,
        players: 3,
        opponentHandSizes: [8, 3],
        hand: ["6C", "7D", "QS", "KH", "AH", "10S"],
        table: [
            {attackCard: "9H", defenseCard: "JH", attackerId: "p2"},
            {attackCard: "9C", attackerId: "p2"},
            {attackCard: "9D", attackerId: "p2"}
        ],
        trumpSuit: "S",
        trumpCard: "7S",
        legalMoves: {canDefend: true, canTake: true, defensesByAttackCard: {"9C": ["QS"], "9D": ["QS"]}},
        ...overrides
    });
}

test.describe("Screen reader support", () => {
    test("cards, table, trump, roles and opponents have accessible names", async ({ page }) => {
        await table(page, defendingThree());
        await page.goto("/");

        const six = page.getByRole("button", {name: "6 of clubs"});
        await expect(six).toHaveAttribute("aria-pressed", "false");
        await six.click();
        await expect(six).toHaveAttribute("aria-pressed", "true");
        await expect(page.getByRole("button", {name: "10 of spades"})).toBeVisible();

        const pairs = page.getByRole("list", {name: "Cards on the table"}).getByRole("listitem");
        await expect(pairs).toHaveText([
            "9 of hearts, beaten by jack of hearts",
            "9 of clubs, not beaten yet",
            "9 of diamonds, not beaten yet"
        ]);

        await expect(page.locator("#trumpSuitHud")).toContainText("Trump: spades");
        await expect(page.locator("#trumpUnderImg")).toHaveAttribute("alt", "Trump card: 7 of spades");
        await expect(page.locator(".hud")).toContainText("Attacker: Krzysztof Elektronik");
        await expect(page.locator(".hud")).toContainText("Defender: Alexandra the Great");
        await expect(page.getByRole("combobox", {name: "Attack card to beat"})).toBeVisible();
        await expect(page.locator("#defendTargetSelect option")).toHaveText(["vs 9 of clubs", "vs 9 of diamonds"]);

        // Opponents: only what the fan shows (at most six backs), and roles in words.
        await expect(page.getByRole("img", {name: "6 or more cards"})).toHaveCount(1);
        await expect(page.getByRole("img", {name: "3 cards"})).toHaveCount(1);
        await expect(page.locator("#seatTop1")).not.toContainText("8");
        await expect(page.locator("#seatTop1 .seat-title .visually-hidden")).toHaveText([/\(bot\)/, /^, attacker$/]);
        await expect(page.locator("#seatTop1 .seat-role-inline")).toHaveAttribute("aria-hidden", "true");
        await expect(page.locator("#mySeatTitle")).toContainText(", defender");
    });

    test("moves, bouts and the result are announced in a polite live region", async ({ page }) => {
        const attackerFirst = syntheticGame({version: 20, hand: ["6C", "8H", "9S", "JC", "QD", "KS"]});
        const ctx = await table(page, attackerFirst);
        await page.goto("/");
        await expect.poll(() => ctx.socket !== null).toBe(true);
        const live = page.locator("#liveAnnouncer");
        await expect(live).toHaveAttribute("aria-live", "polite");

        ctx.game = syntheticGame({
            version: 21,
            hand: ["6C", "8H", "9S", "JC", "QD", "KS"],
            table: [{attackCard: "7H", attackerId: "p2"}],
            legalMoves: {canTake: true}
        });
        ctx.socket.send(JSON.stringify({type: "GAME_UPDATED", version: 21}));
        await expect(live).toContainText("Krzysztof Elektronik attacks with 7 of hearts. Your turn to defend.");

        ctx.game = syntheticGame({
            version: 22,
            hand: ["6C", "9S", "JC", "QD", "KS"],
            table: [{attackCard: "7H", defenseCard: "8H", attackerId: "p2"}]
        });
        ctx.socket.send(JSON.stringify({type: "GAME_UPDATED", version: 22}));
        await expect(live).toContainText("You beat 7 of hearts with 8 of hearts.");

        ctx.game = syntheticGame({version: 23, status: "FINISHED", loserPlayerId: "p2", hand: []});
        ctx.socket.send(JSON.stringify({type: "GAME_UPDATED", version: 23}));
        await expect(live).toContainText("Game over. Krzysztof Elektronik is the durak.");
        await expect(page.locator("#resultTitle")).toBeFocused();
        await expect(page.locator("#resultPanel")).not.toHaveAttribute("aria-live", /.+/);
    });

    test("bot status text is not followed by doubled dots", async ({ page }) => {
        const ctx = await table(page, syntheticGame());
        await page.goto("/");
        await expect.poll(() => ctx.socket !== null).toBe(true);
        await expect(page.locator("#seatTop1 .seat-title")).toBeVisible();

        // Like the real server, later snapshots carry the same thinking state as the socket.
        ctx.game = {...ctx.game, botThinking: {p2: "planning attack..."}};
        ctx.socket.send(JSON.stringify({type: "BOT_THINKING", playerId: "p2", thinking: true, message: "planning attack...", eventAtMs: Date.now()}));
        const note = page.locator("#seatTop1 .bot-thinking-inline");
        await expect(note).toHaveText("planning attack");

        ctx.game = {...ctx.game, botThinking: {p2: "planning defence…"}};
        ctx.socket.send(JSON.stringify({type: "BOT_THINKING", playerId: "p2", thinking: true, message: "planning defence…", eventAtMs: Date.now() + 1}));
        await expect(note).toHaveText("planning defence");
    });

    test("reduced motion stops the thinking-dots animation, pseudo-elements included", async ({ page }) => {
        await page.emulateMedia({reducedMotion: "reduce"});
        const ctx = await table(page, syntheticGame({botThinking: {p2: "planning attack..."}}));
        await page.goto("/");
        await expect.poll(() => ctx.socket !== null).toBe(true);

        const dots = page.locator("#seatTop1 .bot-thinking-dots");
        await expect(dots).toHaveCount(1);
        const style = await dots.evaluate(el => {
            const after = getComputedStyle(el, "::after");
            return {name: after.animationName, content: after.content};
        });
        expect(style.name).toBe("none");
        expect(style.content).toBe("\"...\"");

        // The shared theme rule itself also reaches pseudo-elements and stops repeats.
        const themed = await page.evaluate(() => {
            const probe = document.createElement("div");
            probe.className = "reduced-motion-probe";
            document.body.appendChild(probe);
            const sheet = new CSSStyleSheet();
            sheet.replaceSync(".reduced-motion-probe::before { content: ''; animation: spin 1s infinite; }");
            document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
            const before = getComputedStyle(probe, "::before");
            return {count: before.animationIterationCount, duration: before.animationDuration};
        });
        expect(themed.count).toBe("1");
        expect(parseFloat(themed.duration)).toBeLessThan(0.01);
    });
});
