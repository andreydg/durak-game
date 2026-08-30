import { test, expect } from "@playwright/test";

const legalMoves = {
    canStart: false,
    canAttack: false,
    canDefend: false,
    canTransfer: false,
    canTake: false,
    canEndRound: false,
    attackableCardCodes: [],
    transferableCardCodes: [],
    defensesByAttackCard: {}
};

function player(id, name, hand = []) {
    return {
        id,
        name,
        bot: false,
        joinedAt: "2026-08-28T12:00:00Z",
        team: null,
        handSize: hand.length,
        hand
    };
}

function game(status) {
    const inProgress = status === "IN_PROGRESS";
    return {
        code: "FIN123",
        status,
        version: inProgress ? 12 : 11,
        maxPlayers: 4,
        playerCount: 2,
        createdAt: "2026-08-28T12:00:00Z",
        lastActivityAt: "2026-08-28T12:10:00Z",
        publicRoom: false,
        hostPlayerId: "host",
        attackerPlayerId: inProgress ? "host" : null,
        defenderPlayerId: inProgress ? "guest" : null,
        takingCardsInProgress: false,
        takingPlayerId: null,
        takeLimit: 0,
        loserPlayerId: inProgress ? null : "guest",
        loserPlayerName: inProgress ? null : "Guest",
        loserTeam: null,
        trumpSuit: inProgress ? "S" : null,
        trumpCard: inProgress ? "6S" : null,
        talonSize: inProgress ? 24 : 0,
        table: [],
        players: [
            player("host", "Host", inProgress ? ["6C", "7C", "8C", "9C", "10C", "JC"] : []),
            player("guest", "Guest", [])
        ],
        botThinking: {},
        legalMoves: inProgress ? {...legalMoves, canAttack: true, attackableCardCodes: ["6C", "7C", "8C", "9C", "10C", "JC"]} : legalMoves
    };
}

test("finished game shows the result and host can start a rematch", async ({ page }) => {
    await page.addInitScript(() => {
        sessionStorage.setItem("durak_game_code", "FIN123");
        sessionStorage.setItem("durak_player_id", "host");
        sessionStorage.setItem("durak_player_token", "host-secret");
    });

    let rematchRequest = null;
    await page.route("**/api/games/FIN123**", async route => {
        const request = route.request();
        if (request.method() === "POST" && new URL(request.url()).pathname.endsWith("/rematch")) {
            rematchRequest = {
                body: request.postDataJSON(),
                token: request.headers()["x-durak-token"]
            };
            await route.fulfill({status: 200, contentType: "application/json", body: JSON.stringify(game("IN_PROGRESS"))});
            return;
        }
        await route.fulfill({status: 200, contentType: "application/json", body: JSON.stringify(game("FINISHED"))});
    });

    await page.goto("/");

    await expect(page.locator("#resultPanel")).toBeVisible();
    await expect(page.locator("#resultTitle")).toHaveText("You won!");
    await expect(page.locator("#resultSummary")).toContainText("Guest");
    await expect(page.locator("#playingArea")).toBeHidden();
    await expect(page.locator("#rematchBtn")).toBeVisible();

    await page.click("#rematchBtn");

    await expect(page.locator("#resultPanel")).toBeHidden();
    await expect(page.locator("#playingArea")).toBeVisible();
    await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(6);
    expect(rematchRequest).toEqual({body: {playerId: "host"}, token: "host-secret"});
});

test("non-host loser sees the loss state and waits for the host", async ({ page }) => {
    await page.addInitScript(() => {
        sessionStorage.setItem("durak_game_code", "FIN123");
        sessionStorage.setItem("durak_player_id", "guest");
        sessionStorage.setItem("durak_player_token", "guest-secret");
    });
    await page.route("**/api/games/FIN123**", route => route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(game("FINISHED"))
    }));

    await page.goto("/");

    await expect(page.locator("#resultPanel")).toHaveAttribute("data-outcome", "loss");
    await expect(page.locator("#resultTitle")).toContainText("durak");
    await expect(page.locator("#rematchBtn")).toBeHidden();
    await expect(page.locator("#rematchWaiting")).toBeVisible();
});

test("winner keeps the recorded result after the loser leaves", async ({ page }) => {
    await page.addInitScript(() => {
        sessionStorage.setItem("durak_game_code", "FIN123");
        sessionStorage.setItem("durak_player_id", "host");
        sessionStorage.setItem("durak_player_token", "host-secret");
    });
    const afterDeparture = game("FINISHED");
    afterDeparture.players = afterDeparture.players.filter(candidate => candidate.id === "host");
    afterDeparture.playerCount = 1;
    await page.route("**/api/games/FIN123**", route => route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(afterDeparture)
    }));

    await page.goto("/");

    await expect(page.locator("#resultPanel")).toHaveAttribute("data-outcome", "win");
    await expect(page.locator("#resultTitle")).toHaveText("You won!");
    await expect(page.locator("#resultSummary")).toContainText("Guest");
    await expect(page.locator("#resultTitle")).not.toContainText("Nobody");
});

test("a remotely observed game transition clears stale card selection", async ({ page }) => {
    await page.addInitScript(() => {
        sessionStorage.setItem("durak_game_code", "FIN123");
        sessionStorage.setItem("durak_player_id", "host");
        sessionStorage.setItem("durak_player_token", "host-secret");
    });
    let remoteStatus = "IN_PROGRESS";
    await page.route("**/api/games/FIN123**", route => route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(game(remoteStatus))
    }));

    await page.goto("/");
    await page.locator("#myHand .hand-card-btn").first().click();
    await expect(page.locator("#myHand .hand-card-btn.selected")).toHaveCount(1);

    remoteStatus = "FINISHED";
    await page.evaluate(() => window.refreshGame(false));
    remoteStatus = "IN_PROGRESS";
    await page.evaluate(() => window.refreshGame(false));

    await expect(page.locator("#myHand .hand-card-btn.selected")).toHaveCount(0);
});

test("mobile gameplay keeps the hand full-width and keyboard-accessible", async ({ page }) => {
    await page.setViewportSize({width: 390, height: 844});
    await page.addInitScript(() => {
        sessionStorage.setItem("durak_game_code", "FIN123");
        sessionStorage.setItem("durak_player_id", "host");
        sessionStorage.setItem("durak_player_token", "host-secret");
    });
    await page.route("**/api/games/FIN123**", route => route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(game("IN_PROGRESS"))
    }));

    await page.goto("/");

    const handRail = page.locator("#myHand");
    const railBox = await handRail.boundingBox();
    expect(railBox?.width).toBeGreaterThan(330);

    const firstCard = page.locator("#myHand .hand-card-btn").first();
    await firstCard.scrollIntoViewIfNeeded();
    await firstCard.focus();
    await page.keyboard.press("Enter");
    await expect(firstCard).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator(".action-strip")).toBeVisible();
});
