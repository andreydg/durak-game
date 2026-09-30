import { test, expect } from "@playwright/test";
import { seedSession, syntheticGame } from "./support/synthetic.js";

const CODE = "SYNTH2";

/** Opening attack available for "me": the attacker in a 2-player game, empty table. */
function attackerGame(overrides = {}) {
    const game = syntheticGame({
        version: 12,
        attackerPlayerId: "me",
        defenderPlayerId: "p2",
        hand: ["6C", "7D", "8H", "9S", "JC", "QD"],
        legalMoves: {canAttack: true, attackableCardCodes: ["6C", "7D", "8H", "9S", "JC", "QD"]},
        ...overrides
    });
    return game;
}

async function routeGame(page, handlers) {
    await page.routeWebSocket("**/ws/games/**", () => {});
    await page.route(`**/api/games/${CODE}**`, async route => {
        const request = route.request();
        const path = new URL(request.url()).pathname;
        const handler = handlers[`${request.method()} ${path.slice(`/api/games/${CODE}`.length) || "/"}`];
        if (handler) {
            await handler(route);
            return;
        }
        await route.fulfill({status: 404, contentType: "application/json", body: JSON.stringify({message: "unexpected"})});
    });
}

function json(route, status, body) {
    return route.fulfill({status, contentType: "application/json", body: JSON.stringify(body)});
}

test.describe("Action responses and API errors", () => {
    test("a delayed action response cannot roll back newer state", async ({ page }) => {
        await seedSession(page);
        let current = attackerGame();
        let releaseAttack;
        const attackHeld = new Promise(resolve => { releaseAttack = resolve; });
        let attackArrived;
        const attackSeen = new Promise(resolve => { attackArrived = resolve; });
        await routeGame(page, {
            "GET /": route => json(route, 200, current),
            "POST /attack": async route => {
                attackArrived();
                await attackHeld;
                // The server's answer to the attack itself (version 13) is older than what the
                // client meanwhile read (version 14: the bot already beat the card).
                await json(route, 200, attackerGame({
                    version: 13,
                    hand: ["7D", "8H", "9S", "JC", "QD"],
                    table: [{attackCard: "6C", attackerId: "me"}],
                    legalMoves: {}
                }));
            }
        });

        await page.goto("/");
        await page.locator('#myHand [data-card-code="6C"]').click();
        await page.click("#attackBtn");
        await attackSeen;

        current = attackerGame({
            version: 14,
            hand: ["7D", "8H", "9S", "JC", "QD"],
            table: [{attackCard: "6C", defenseCard: "10C", attackerId: "me"}],
            legalMoves: {canEndRound: true}
        });
        await page.evaluate(() => window.refreshGame(false));
        await expect(page.locator("#battleCards .battle-card.defense")).toHaveCount(1);

        releaseAttack();
        await expect(page.locator("#attackBtn")).not.toHaveAttribute("aria-busy", "true");
        await page.waitForTimeout(100);
        await expect(page.locator("#battleCards .battle-card.defense")).toHaveCount(1);
        await expect(page.locator("#endRoundBtn")).toBeEnabled();
        await expect(page.locator("#appAlert")).toBeHidden();
    });

    test("a proxy's HTML error page produces a readable message", async ({ page }) => {
        await seedSession(page);
        await routeGame(page, {
            "GET /": route => json(route, 200, attackerGame()),
            "POST /attack": route => route.fulfill({
                status: 502,
                contentType: "text/html",
                body: "<html><body><h1>502 Bad Gateway</h1></body></html>"
            })
        });

        await page.goto("/");
        await page.locator('#myHand [data-card-code="6C"]').click();
        await page.click("#attackBtn");

        const alert = page.locator("#appAlert");
        await expect(alert).toContainText("Attack: The server is unavailable right now");
        await expect(alert).not.toContainText("Unexpected token");
        await expect(page.locator("#gameView")).toBeVisible();
    });

    test("a refresh that finds the room gone returns to the lobby", async ({ page }) => {
        await seedSession(page);
        let gone = false;
        await routeGame(page, {
            "GET /": route => gone
                ? json(route, 404, {message: "Game not found"})
                : json(route, 200, attackerGame())
        });
        await page.goto("/");
        await expect(page.locator("#gameView")).toBeVisible();

        gone = true;
        await page.evaluate(() => window.refreshGame(false));

        await expect(page.locator("#lobbyView")).toBeVisible();
        await expect(page.locator("#appAlert")).toContainText(`Room ${CODE} no longer exists.`);
        expect(await page.evaluate(() => sessionStorage.getItem("durak_game_code"))).toBe("");
    });

    test("an action on an expired room returns to the lobby", async ({ page }) => {
        await seedSession(page);
        await routeGame(page, {
            "GET /": route => json(route, 200, attackerGame()),
            "POST /attack": route => json(route, 410, {message: "Room expired due to inactivity."})
        });
        await page.goto("/");
        await page.locator('#myHand [data-card-code="6C"]').click();
        await page.click("#attackBtn");

        await expect(page.locator("#lobbyView")).toBeVisible();
        await expect(page.locator("#appAlert")).toContainText(`Room ${CODE} expired due to inactivity.`);
        expect(await page.evaluate(() => sessionStorage.getItem("durak_player_token"))).toBe("");
    });

    test("a move refused with 404 keeps the seat and re-reads the room", async ({ page }) => {
        await seedSession(page);
        const defending = syntheticGame({
            version: 12,
            table: [{attackCard: "9H"}],
            hand: ["6C", "7D", "JH", "9S", "JC", "QD"],
            legalMoves: {canDefend: true, canTake: true, defensesByAttackCard: {"9H": ["JH"]}}
        });
        let readsAfterDefend = 0;
        let defended = false;
        await routeGame(page, {
            "GET /": route => {
                if (defended) readsAfterDefend++;
                return json(route, 200, defending);
            },
            // Game.defend throws NoSuchElementException, which the server maps to 404.
            "POST /defend": route => {
                defended = true;
                return json(route, 404, {message: "Attack card to defend not found"});
            }
        });

        await page.goto("/");
        await page.locator('#myHand [data-card-code="JH"]').click();
        await page.click("#defendBtn");

        await expect(page.locator("#appAlert")).toContainText("Defend: Attack card to defend not found");
        await expect.poll(() => readsAfterDefend).toBeGreaterThanOrEqual(1);
        await expect(page.locator("#playingArea")).toBeVisible();
        expect(await page.evaluate(() => sessionStorage.getItem("durak_game_code"))).toBe(CODE);
    });

    test("a move refused with 404 in a vanished room ends the seat once the re-read confirms it", async ({ page }) => {
        await seedSession(page);
        let gone = false;
        await routeGame(page, {
            "GET /": route => gone
                ? json(route, 404, {message: "Game not found"})
                : json(route, 200, attackerGame()),
            "POST /attack": route => {
                gone = true;
                return json(route, 404, {message: "Game not found"});
            }
        });
        await page.goto("/");
        await page.locator('#myHand [data-card-code="6C"]').click();
        await page.click("#attackBtn");

        await expect(page.locator("#lobbyView")).toBeVisible();
        await expect(page.locator("#appAlert")).toContainText(`Room ${CODE} no longer exists.`);
        expect(await page.evaluate(() => sessionStorage.getItem("durak_game_code"))).toBe("");
    });

    test("a transient failure keeps the seat and explains itself", async ({ page }) => {
        await seedSession(page);
        await routeGame(page, {
            "GET /": route => json(route, 200, attackerGame()),
            "POST /attack": route => json(route, 503, {message: "Game storage is temporarily unavailable. Please try again."})
        });
        await page.goto("/");
        await page.locator('#myHand [data-card-code="6C"]').click();
        await page.click("#attackBtn");

        await expect(page.locator("#appAlert")).toContainText("Attack: Game storage is temporarily unavailable");
        await expect(page.locator("#gameView")).toBeVisible();
        expect(await page.evaluate(() => sessionStorage.getItem("durak_game_code"))).toBe(CODE);
    });
});
