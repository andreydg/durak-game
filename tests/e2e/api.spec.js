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
    let socket;
    await page.routeWebSocket("**/ws/games/**", ws => { socket = ws; });
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
    return () => socket;
}

function json(route, status, body) {
    return route.fulfill({status, contentType: "application/json", body: JSON.stringify(body)});
}

test.describe("Action responses and API errors", () => {
    test("an End round without bout identity displays the reload instruction after refetch", async ({page}) => {
        await seedSession(page);
        // Omitting the displayed identity reproduces the body sent by pre-deploy tabs.
        const current = attackerGame({
            hand: ["KD"], opponentHandSizes: [1],
            table: [{attackCard: "6H", defenseCard: "7H", attackerId: "me"}],
            legalMoves: {canEndRound: true}
        });
        delete current.boutId; // Remove the synthetic builder's default too.
        let reads = 0;
        const requests = [];
        await routeGame(page, {
            "GET /": route => { reads++; return json(route, 200, current); },
            "POST /end-round": route => {
                requests.push(route.request().postDataJSON());
                return json(route, 409, {message: "This page is out of date. Reload to continue."});
            }
        });
        await page.goto("/");
        await expect(page.locator("#endRoundBtn")).toBeEnabled();
        const readsBeforePress = reads;
        await page.click("#endRoundBtn");
        await expect.poll(() => reads).toBeGreaterThan(readsBeforePress);
        await expect(page.locator("#appAlert")).toBeVisible();
        await expect(page.locator("#appAlert")).toHaveText("End round: This page is out of date. Reload to continue.");
        await expect(page.locator("#battleCards .battle-pair")).toHaveCount(1);
        expect(requests).toEqual([{playerId: "me"}]);
    });

    for (const finishesGame of [false, true]) {
        test(`the attacker sees the final defence before ${finishesGame ? "the result" : "the next bout"} without End round`, async ({ page }) => {
            await seedSession(page);
            let current = attackerGame({
                hand: ["6D"],
                opponentHandSizes: [1],
                table: [{attackCard: "6H", attackerId: "me"}],
                legalMoves: {}
            });
            let endRoundRequests = 0;
            page.on("request", request => {
                if (request.method() === "POST" && new URL(request.url()).pathname.endsWith("/end-round")) {
                    endRoundRequests++;
                }
            });
            const socket = await routeGame(page, {"GET /": route => json(route, 200, current)});
            await page.goto("/");
            await expect(page.locator("#battleCards .battle-pair")).toHaveCount(1);
            await expect.poll(() => Boolean(socket())).toBe(true);

            current = attackerGame({
                version: 13, hand: ["6D"], opponentHandSizes: [0],
                canAutoEndRound: true,
                table: [{attackCard: "6H", defenseCard: "7H", attackerId: "me"}],
                legalMoves: {canEndRound: true}
            });
            socket().send(JSON.stringify({type: "GAME_UPDATED", version: 13}));
            await expect(page.locator("#battleCards .battle-card.defense")).toHaveCount(1);
            await expect(page.locator("#endRoundBtn")).toBeEnabled();
            await expect(page.locator("#actionHint")).toContainText("This bout ends automatically after a short pause.");
            await expect(page.locator("#liveAnnouncer")).toContainText("beats 6 of hearts with 7 of hearts.");
            await expect(page.locator("#liveAnnouncer")).toContainText("the bout will end automatically");

            // The server timer is controlled by this fixture. Merely refreshing during its pause
            // must not hide the final defence or submit an End round action.
            await page.evaluate(() => window.refreshGame(false));
            await expect(page.locator("#battleCards .battle-card.defense")).toHaveCount(1);
            expect(endRoundRequests).toBe(0);
            if (finishesGame) {
                current = syntheticGame({version: 14, boutId: "synthetic-deal:2", status: "FINISHED", loserPlayerId: "me",
                    hand: ["6D"], opponentHandSizes: [0]});
            } else {
                current = syntheticGame({version: 14, boutId: "synthetic-deal:2", hand: ["6D", "8C", "9C", "10C", "JC", "QC"],
                    opponentHandSizes: [6], table: [], legalMoves: {}});
            }
            socket().send(JSON.stringify({type: "GAME_UPDATED", version: 14}));
            if (finishesGame) {
                await expect(page.locator("#resultPanel")).toBeVisible();
                await expect(page.locator("#resultTitle")).toHaveText("You’re the durak");
            } else {
                await expect(page.locator("#battleCards .battle-pair")).toHaveCount(0);
                await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(6);
                await expect(page.locator("#endRoundBtn")).toBeDisabled();
            }
            expect(endRoundRequests).toBe(0);
            await expect(page.locator("#appAlert")).toBeHidden();
        });
    }

    for (const finishesGame of [false, true]) {
        test(`a stale End round retains the pressed bout and quietly accepts ${finishesGame ? "the result" : "the newer bout"}`, async ({ page }) => {
            await seedSession(page);
            let current = attackerGame({
                boutId: "deal:1", canAutoEndRound: true,
                hand: ["6D"], opponentHandSizes: [0],
                table: [{attackCard: "6H", defenseCard: "7H", attackerId: "me"}],
                legalMoves: {canEndRound: true}
            });
            let releaseEnd;
            const endHeld = new Promise(resolve => { releaseEnd = resolve; });
            let endArrived;
            const endSeen = new Promise(resolve => { endArrived = resolve; });
            const requests = [];
            await routeGame(page, {
                "GET /": route => json(route, 200, current),
                "POST /end-round": async route => {
                    requests.push({body: route.request().postDataJSON(), token: route.request().headers()["x-durak-token"]});
                    endArrived();
                    await endHeld;
                    // The old bout has already ended. The server returns its unchanged current
                    // snapshot with 200; it must not apply this press to the new bout.
                    await json(route, 200, current);
                }
            });
            await page.goto("/");
            await expect(page.locator("#endRoundBtn")).toBeEnabled();
            await page.click("#endRoundBtn");
            await endSeen;

            current = finishesGame
                ? syntheticGame({version: 14, boutId: "deal:2", status: "FINISHED", loserPlayerId: "me", hand: ["6D"]})
                : attackerGame({version: 14, boutId: "deal:2", canAutoEndRound: true,
                    hand: ["6D"], opponentHandSizes: [0],
                    table: [{attackCard: "8H", defenseCard: "9H", attackerId: "me"}],
                    legalMoves: {canEndRound: true}});
            await page.evaluate(() => window.refreshGame(false));
            if (finishesGame) {
                await expect(page.locator("#resultPanel")).toBeVisible();
            } else {
                await expect(page.locator("#battleCards .battle-pair")).toHaveAttribute("data-attack-card", "8H");
                await expect(page.locator("#endRoundBtn")).toHaveAttribute("aria-disabled", "true");
            }
            expect(requests).toEqual([{body: {playerId: "me", boutId: "deal:1"}, token: "me-secret"}]);

            releaseEnd();
            // Enabled cards prove the request settled, rather than merely observing no alert
            // while the POST is still in flight (the hand is hidden in the finished view).
            await expect(page.locator('#myHand [data-card-code="6D"]')).not.toHaveAttribute("aria-disabled", "true");
            await page.evaluate(() => window.refreshGame(false));
            await expect(page.locator("#appAlert")).toBeHidden();
            if (finishesGame) {
                await expect(page.locator("#resultTitle")).toHaveText("You’re the durak");
            } else {
                await expect(page.locator("#battleCards .battle-pair")).toHaveAttribute("data-attack-card", "8H");
                await expect(page.locator("#endRoundBtn")).toBeEnabled();
            }
            expect(requests).toHaveLength(1);
        });
    }

    test("the server flag controls automatic hints and consecutive bouts are announced once each", async ({ page }) => {
        await seedSession(page);
        // Empty defender + covered table alone must not promise an automatic end.
        let current = attackerGame({
            boutId: "deal:1", canAutoEndRound: false,
            hand: ["6D"], opponentHandSizes: [0],
            table: [{attackCard: "6H", defenseCard: "7H", attackerId: "me"}],
            legalMoves: {canEndRound: true}
        });
        await routeGame(page, {"GET /": route => json(route, 200, current)});
        await page.goto("/");
        await expect(page.locator("#actionHint")).toHaveText("All attacks are defended. Press End round to finish this bout.");
        const automaticAnnouncements = page.locator("#liveAnnouncer p").filter({hasText: "the bout will end automatically"});
        await expect(automaticAnnouncements).toHaveCount(0);

        current = {...current, version: 13, canAutoEndRound: true};
        await page.evaluate(() => window.refreshGame(false));
        await expect(page.locator("#actionHint")).toContainText("This bout ends automatically after a short pause.");
        await expect(automaticAnnouncements).toHaveCount(1);

        // A skipped bout boundary/rematch can reuse the opening card. Identity, not that
        // card or the previous flag, distinguishes the second automatic pause.
        current = {...current, version: 14, boutId: "next-deal:1"};
        await page.evaluate(() => window.refreshGame(false));
        await expect(automaticAnnouncements).toHaveCount(2);
        await page.evaluate(() => window.refreshGame(false));
        await expect(automaticAnnouncements).toHaveCount(2);
        await expect(page.locator("#appAlert")).toBeHidden();
    });

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
