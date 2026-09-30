import { test, expect } from "@playwright/test";
import { seedSession, syntheticGame } from "./support/synthetic.js";

const CODE = "SYNTH2";

/**
 * Serves a synthetic game and lets each test decide how POST /leave answers.
 * Returns the list of leave requests seen.
 */
async function serveWithLeave(page, game, answerLeave) {
    const leaves = [];
    await seedSession(page);
    await page.routeWebSocket("**/ws/games/**", () => {});
    await page.route(`**/api/games/${CODE}**`, async route => {
        const request = route.request();
        if (request.method() === "POST" && new URL(request.url()).pathname.endsWith("/leave")) {
            leaves.push(request.postDataJSON());
            await answerLeave(route);
            return;
        }
        await route.fulfill({status: 200, contentType: "application/json", body: JSON.stringify(game)});
    });
    return leaves;
}

const emptyOk = route => route.fulfill({status: 200, body: ""});

test.describe("Leaving a room", () => {
    test("leaving a game in progress asks first, and Escape keeps the seat", async ({ page }) => {
        const leaves = await serveWithLeave(page, syntheticGame(), emptyOk);
        await page.goto("/");
        await expect(page.locator("#playingArea")).toBeVisible();

        await page.locator("#leaveBtn").focus();
        await page.keyboard.press("Enter");

        const dialog = page.getByRole("dialog", {name: "Leave this game?"});
        await expect(dialog).toBeVisible();
        await expect(page.locator("#leaveCancelBtn")).toBeFocused();
        // Focus is trapped: Tab only ever reaches the dialog's buttons (or the browser UI).
        for (let i = 0; i < 4; i++) {
            await page.keyboard.press("Tab");
            const where = await page.evaluate(() => {
                const active = document.activeElement;
                if (!active || active === document.body) return "browser";
                return document.getElementById("leaveDialog").contains(active) ? "dialog" : active.id || active.tagName;
            });
            expect(["dialog", "browser"]).toContain(where);
        }

        await page.keyboard.press("Escape");
        await expect(dialog).toBeHidden();
        await expect(page.locator("#leaveBtn")).toBeFocused();
        await expect(page.locator("#gameView")).toBeVisible();
        expect(leaves).toEqual([]);
    });

    test("confirming leaves, disabling Leave while the request is pending", async ({ page }) => {
        let release;
        const held = new Promise(resolve => { release = resolve; });
        const leaves = await serveWithLeave(page, syntheticGame(), async route => {
            await held;
            await emptyOk(route);
        });
        await page.goto("/");

        await page.click("#leaveBtn");
        await page.getByRole("button", {name: "Leave game"}).click();

        const leaveBtn = page.locator("#leaveBtn");
        await expect(leaveBtn).toHaveAttribute("aria-busy", "true");
        await expect(leaveBtn).toBeDisabled();
        await leaveBtn.click({force: true});

        release();
        await expect(page.locator("#lobbyView")).toBeVisible();
        expect(leaves).toEqual([{playerId: "me"}]);
        expect(await page.evaluate(() => sessionStorage.getItem("durak_game_code"))).toBe("");
        await expect(page.locator("#appAlert")).toBeHidden();
    });

    test("the question goes away if the room disappears while it is open", async ({ page }) => {
        let gone = false;
        await seedSession(page);
        await page.routeWebSocket("**/ws/games/**", () => {});
        await page.route(`**/api/games/${CODE}**`, route => route.fulfill(gone
            ? {status: 404, contentType: "application/json", body: JSON.stringify({message: "Game not found"})}
            : {status: 200, contentType: "application/json", body: JSON.stringify(syntheticGame())}));
        await page.goto("/");
        await page.click("#leaveBtn");
        await expect(page.getByRole("dialog")).toBeVisible();

        gone = true;
        await page.evaluate(() => window.refreshGame(false));

        await expect(page.getByRole("dialog")).toBeHidden();
        await expect(page.locator("#lobbyView")).toBeVisible();
        await expect(page.locator("#appAlert")).toContainText(`Room ${CODE} no longer exists.`);
    });

    test("the question goes away when the game ends behind it", async ({ page }) => {
        let finished = false;
        const leaves = [];
        await seedSession(page);
        await page.routeWebSocket("**/ws/games/**", () => {});
        await page.route(`**/api/games/${CODE}**`, async route => {
            if (new URL(route.request().url()).pathname.endsWith("/leave")) {
                leaves.push(route.request().postDataJSON());
                await emptyOk(route);
                return;
            }
            await route.fulfill({
                status: 200,
                contentType: "application/json",
                body: JSON.stringify(finished
                    ? syntheticGame({version: 9, status: "FINISHED", loserPlayerId: "p2", hand: []})
                    : syntheticGame({version: 8}))
            });
        });
        await page.goto("/");
        await page.click("#leaveBtn");
        await expect(page.getByRole("dialog")).toBeVisible();

        finished = true;
        await page.evaluate(() => window.refreshGame(false));

        await expect(page.getByRole("dialog")).toBeHidden();
        await expect(page.locator("#resultPanel")).toBeVisible();
        await expect(page.locator("#resultTitle")).toBeFocused();
        expect(leaves).toEqual([]);
    });

    test("an action that fails after the player left does not raise a stale error", async ({ page }) => {
        let releaseAttack;
        const attackHeld = new Promise(resolve => { releaseAttack = resolve; });
        await seedSession(page);
        await page.routeWebSocket("**/ws/games/**", () => {});
        await page.route(`**/api/games/${CODE}**`, async route => {
            const path = new URL(route.request().url()).pathname;
            if (path.endsWith("/attack")) {
                await attackHeld;
                await route.fulfill({status: 404, contentType: "application/json", body: JSON.stringify({message: "Game not found"})});
                return;
            }
            if (path.endsWith("/leave")) {
                await emptyOk(route);
                return;
            }
            await route.fulfill({status: 200, contentType: "application/json", body: JSON.stringify(syntheticGame({
                attackerPlayerId: "me",
                defenderPlayerId: "p2",
                hand: ["6C", "7D", "8H", "9S", "JC", "QD"],
                legalMoves: {canAttack: true, attackableCardCodes: ["6C"]}
            }))});
        });
        await page.goto("/");
        await page.locator('#myHand [data-card-code="6C"]').click();
        await page.click("#attackBtn");
        await page.click("#leaveBtn");
        await page.getByRole("button", {name: "Leave game"}).click();
        await expect(page.locator("#lobbyView")).toBeVisible();

        releaseAttack();
        await page.waitForTimeout(300);
        await expect(page.locator("#appAlert")).toBeHidden();
        await expect(page.locator("#lobbyView")).toBeVisible();
    });

    test("the Stay button keeps the seat", async ({ page }) => {
        const leaves = await serveWithLeave(page, syntheticGame(), emptyOk);
        await page.goto("/");

        await page.click("#leaveBtn");
        await page.getByRole("button", {name: "Stay"}).click();

        await expect(page.getByRole("dialog")).toBeHidden();
        await expect(page.locator("#playingArea")).toBeVisible();
        expect(leaves).toEqual([]);
    });

    test("a waiting room is left without a confirmation", async ({ page }) => {
        const leaves = await serveWithLeave(page, syntheticGame({status: "LOBBY"}), emptyOk);
        await page.goto("/");
        await expect(page.locator("#roomWaitingLine")).toBeVisible();

        await page.click("#leaveBtn");

        await expect(page.locator("#lobbyView")).toBeVisible();
        expect(leaves).toHaveLength(1);
    });

    for (const [status, text] of [
        [403, `This browser's seat in room ${CODE} was no longer valid`],
        [404, `Room ${CODE} no longer exists`],
        [410, `Room ${CODE} expired due to inactivity`]
    ]) {
        test(`a ${status} from leave clears the seat and explains why`, async ({ page }) => {
            await serveWithLeave(page, syntheticGame({status: "FINISHED", loserPlayerId: "p2"}), route => route.fulfill({
                status,
                contentType: "application/json",
                body: JSON.stringify({message: "irrelevant"})
            }));
            await page.goto("/");
            await expect(page.locator("#resultPanel")).toBeVisible();

            await page.click("#leaveBtn");

            await expect(page.locator("#lobbyView")).toBeVisible();
            await expect(page.locator("#appAlert")).toContainText(`${text}, so you are back in the lobby.`);
            expect(await page.evaluate(() => sessionStorage.getItem("durak_player_token"))).toBe("");
        });
    }

    for (const [label, answer] of [
        ["a network error", route => route.abort("connectionfailed")],
        ["a 503", route => route.fulfill({status: 503, contentType: "application/json", body: JSON.stringify({message: "Game storage is temporarily unavailable. Please try again."})})]
    ]) {
        test(`${label} from leave keeps the seat`, async ({ page }) => {
            await serveWithLeave(page, syntheticGame({status: "LOBBY"}), answer);
            await page.goto("/");

            await page.click("#leaveBtn");

            await expect(page.locator("#appAlert")).toContainText(`You are still in room ${CODE}.`);
            await expect(page.locator("#gameView")).toBeVisible();
            await expect(page.locator("#leaveBtn")).toBeEnabled();
            expect(await page.evaluate(() => sessionStorage.getItem("durak_game_code"))).toBe(CODE);
        });
    }
});
