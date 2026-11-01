import { test, expect } from "@playwright/test";
import { seedSession, syntheticGame } from "./support/synthetic.js";

const CODE = "SYNTH2";

/*
 * The bot led 6D and hearts are trump, so 6H can either transfer (same rank, nothing beaten yet)
 * or beat the 6D. Dropping it on the 6D defends; dropping it on the open felt transfers.
 */
function sixesGame() {
    return syntheticGame({
        version: 7,
        attackerPlayerId: "p2",
        defenderPlayerId: "me",
        trumpSuit: "H",
        trumpCard: "9H",
        hand: ["6H", "7H", "JH", "QC", "KD"],
        table: [{attackCard: "6D", attackerId: "p2"}],
        legalMoves: {
            canDefend: true,
            canTransfer: true,
            canTake: true,
            transferableCardCodes: ["6H"],
            defensesByAttackCard: {"6D": ["6H", "7H", "JH", "KD"]}
        }
    });
}

/** Serves the game and records the table moves the page posts. */
async function routeMoves(page) {
    const moves = [];
    await page.routeWebSocket("**/ws/games/**", () => {});
    await page.route(`**/api/games/${CODE}**`, async route => {
        const request = route.request();
        const action = new URL(request.url()).pathname.slice(`/api/games/${CODE}`.length);
        if (request.method() === "POST" && ["/attack", "/defend", "/transfer"].includes(action)) {
            moves.push({action, body: request.postDataJSON()});
        }
        await route.fulfill({status: 200, contentType: "application/json", body: JSON.stringify(sixesGame())});
    });
    return moves;
}

async function centre(locator) {
    const box = await locator.boundingBox();
    return {x: box.x + box.width / 2, y: box.y + box.height / 2};
}

test.describe("Dragging a card to the table", () => {
    test.beforeEach(async ({page}) => {
        await seedSession(page);
    });

    test("a card dropped on an attack card it beats defends it, even when it could transfer", async ({page}) => {
        const moves = await routeMoves(page);
        await page.goto("/");
        const pair = page.locator('#battleCards .battle-pair[data-attack-card="6D"]');
        await expect(pair).toBeVisible();

        await page.locator('#myHand [data-card-code="6H"]').dragTo(pair);

        await expect.poll(() => moves.length).toBe(1);
        expect(moves[0]).toEqual({action: "/defend", body: {playerId: "me", attackCard: "6D", defenseCard: "6H"}});
    });

    test("a card dropped on the open felt transfers", async ({page}) => {
        const moves = await routeMoves(page);
        await page.goto("/");
        await expect(page.locator("#battleCards .battle-pair")).toBeVisible();

        await page.locator('#myHand [data-card-code="6H"]').dragTo(page.locator("#battleCards"), {
            targetPosition: {x: 8, y: 8}
        });

        await expect.poll(() => moves.length).toBe(1);
        expect(moves[0]).toEqual({action: "/transfer", body: {playerId: "me", card: "6H"}});
    });

    test("the outline shows what the drop will do", async ({page}) => {
        const moves = await routeMoves(page);
        await page.goto("/");
        const row = page.locator("#battleCards");
        const pair = row.locator('.battle-pair[data-attack-card="6D"]');
        await expect(pair).toBeVisible();

        // 6H over the 6D: the pair is outlined because the drop defends it.
        const card = await centre(page.locator('#myHand [data-card-code="6H"]'));
        await page.mouse.move(card.x, card.y);
        await page.mouse.down();
        const onPair = await centre(pair);
        await page.mouse.move(onPair.x, onPair.y, {steps: 8});
        await expect(pair).toHaveClass(/battle-pair-drop-target/);
        await expect(row).not.toHaveClass(/battle-drop-target/);

        // Over the open felt the whole row is outlined: the drop transfers.
        const box = await row.boundingBox();
        await page.mouse.move(box.x + 8, box.y + 8, {steps: 8});
        await expect(row).toHaveClass(/battle-drop-target/);
        await expect(pair).not.toHaveClass(/battle-pair-drop-target/);
        await page.mouse.up();
        await expect.poll(() => moves.map(move => move.action)).toEqual(["/transfer"]);
        await expect(row).not.toHaveClass(/battle-drop-target/);

        // QC beats nothing and cannot transfer: no outline anywhere.
        const queen = await centre(page.locator('#myHand [data-card-code="QC"]'));
        await page.mouse.move(queen.x, queen.y);
        await page.mouse.down();
        await page.mouse.move(onPair.x, onPair.y, {steps: 8});
        await expect(row).not.toHaveClass(/battle-drop-target/);
        await expect(pair).not.toHaveClass(/battle-pair-drop-target/);
        await page.mouse.up();
    });
});
