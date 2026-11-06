import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import { DECK, seedSession } from "./support/synthetic.js";
import { centre, routeMoves, sixesGame } from "./support/table-moves.js";
import { lands, reveal, touchscreen } from "./support/touch.js";

const PHONE = {width: 390, height: 844};
const TABLET = {width: 820, height: 1180}; // an iPad in portrait

/*
 * Phones and tablets drag cards with a finger (touchdrag.js) to the same targets, with the same
 * rules, as a mouse does with HTML5 drag and drop (drag-drop.spec.js). These tests send real
 * touch input through Chromium, so each card's touch-action decides between a drag and a scroll
 * as it does on a device. In sixesGame the bot led 6D, hearts are trump, and 6H can transfer or
 * beat the 6D.
 */
test.describe("Dragging a card with a finger", () => {
    test.use({hasTouch: true, isMobile: true, viewport: PHONE});

    test.beforeEach(async ({page}) => {
        await seedSession(page);
    });

    for (const [device, viewport] of [["phone", PHONE], ["tablet", TABLET]]) {
        test(`on a ${device}, a card slid onto an attack card it beats defends it`, async ({page}) => {
            await page.setViewportSize(viewport);
            const moves = await routeMoves(page);
            await page.goto("/");
            const pair = page.locator('#battleCards .battle-pair[data-attack-card="6D"]');
            await expect(pair).toBeVisible();

            const touch = await touchscreen(page);
            await touch.drag(await centre(page.locator('#myHand [data-card-code="6H"]')), await centre(pair));

            await expect.poll(() => moves.length).toBe(1);
            expect(moves[0]).toEqual({action: "/defend", body: {playerId: "me", attackCard: "6D", defenseCard: "6H"}});
        });

        test(`on a ${device}, a card slid onto the open felt transfers`, async ({page}) => {
            await page.setViewportSize(viewport);
            const moves = await routeMoves(page);
            await page.goto("/");
            const row = page.locator("#battleCards");
            await expect(row.locator(".battle-pair")).toBeVisible();

            const box = await row.boundingBox();
            const touch = await touchscreen(page);
            await touch.drag(await centre(page.locator('#myHand [data-card-code="6H"]')), {x: box.x + 8, y: box.y + 8});

            await expect.poll(() => moves.length).toBe(1);
            expect(moves[0]).toEqual({action: "/transfer", body: {playerId: "me", card: "6H"}});
        });
    }

    test("on a page that scrolls, sliding a card up drags it instead of scrolling the page", async ({page}) => {
        // Shorter than the table: the page scrolls, so the card's touch-action decides the gesture.
        await page.setViewportSize({width: PHONE.width, height: 640});
        const moves = await routeMoves(page);
        await page.goto("/");
        const pair = page.locator('#battleCards .battle-pair[data-attack-card="6D"]');
        await expect(pair).toBeVisible();
        expect(await page.evaluate(() => document.documentElement.scrollHeight > window.innerHeight)).toBe(true);
        const before = await page.evaluate(() => window.scrollY);

        const touch = await touchscreen(page);
        await touch.drag(await centre(page.locator('#myHand [data-card-code="6H"]')), await centre(pair));

        await expect.poll(() => moves.length).toBe(1);
        expect(moves[0]).toEqual({action: "/defend", body: {playerId: "me", attackCard: "6D", defenseCard: "6H"}});
        expect(await page.evaluate(() => window.scrollY)).toBe(before);
    });

    test("held just under the sticky header, a dragged card scrolls the table back into view", async ({page}) => {
        await page.setViewportSize({width: PHONE.width, height: 640});
        const moves = await routeMoves(page);
        await page.goto("/");
        await expect(page.locator('#battleCards .battle-pair[data-attack-card="6D"]')).toBeVisible();
        await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
        const scrolled = await page.evaluate(() => window.scrollY);
        expect(scrolled).toBeGreaterThan(0);
        const header = await page.locator("#gameView .room-header").boundingBox();

        const touch = await touchscreen(page);
        const from = await centre(page.locator('#myHand [data-card-code="6H"]'));
        await touch.down(from);
        await touch.move({x: from.x, y: from.y - 40}, {steps: 4});
        await touch.move({x: from.x, y: header.y + header.height + 8}, {steps: 8});
        await expect.poll(() => page.evaluate(() => window.scrollY)).toBeLessThan(scrolled);

        // Out of the edge band the page stops, and the pair now in view takes the card.
        const pair = page.locator('#battleCards .battle-pair[data-attack-card="6D"]');
        await touch.move(await centre(pair), {steps: 6});
        await touch.up();
        await expect.poll(() => moves.length).toBe(1);
        expect(moves[0]).toEqual({action: "/defend", body: {playerId: "me", attackCard: "6D", defenseCard: "6H"}});
    });

    test("the lifted card and the outline show what the drop will do, and lifting off the table cancels", async ({page}) => {
        const moves = await routeMoves(page);
        await page.goto("/");
        const row = page.locator("#battleCards");
        const pair = row.locator('.battle-pair[data-attack-card="6D"]');
        await expect(pair).toBeVisible();
        const card = page.locator('#myHand [data-card-code="6H"]');
        const ghost = page.locator(".touch-drag-ghost");

        const touch = await touchscreen(page);
        const from = await centre(card);
        await touch.down(from);
        await touch.move({x: from.x, y: from.y - 40}, {steps: 4});
        await expect(ghost).toHaveCount(1);
        await expect(ghost).toHaveAttribute("aria-hidden", "true");
        await expect(card).toHaveClass(/dragging/);

        // Over the 6D the pair is outlined: the drop defends it.
        await touch.move(await centre(pair));
        await expect(pair).toHaveClass(/battle-pair-drop-target/);
        await expect(row).not.toHaveClass(/battle-drop-target/);

        // Over the open felt the whole row is outlined: the drop transfers.
        const box = await row.boundingBox();
        await touch.move({x: box.x + 8, y: box.y + 8});
        await expect(row).toHaveClass(/battle-drop-target/);
        await expect(pair).not.toHaveClass(/battle-pair-drop-target/);

        // Back over the hand nothing is outlined, and lifting there plays and selects nothing.
        await touch.move(from);
        await expect(row).not.toHaveClass(/battle-drop-target/);
        await touch.up();
        await expect(ghost).toHaveCount(0);
        await expect(card).not.toHaveClass(/dragging/);
        await expect(card).toHaveAttribute("aria-pressed", "false");
        // Some browsers follow the lifted finger with a click on the card: that is not a selection.
        // It is a pointer's click, as a browser sends it (touch, one press), unlike Enter's.
        await card.evaluate(button => button.dispatchEvent(new PointerEvent("click", {
            bubbles: true, cancelable: true, composed: true, detail: 1, pointerId: 1, pointerType: "touch", isPrimary: true
        })));
        await expect(card).toHaveAttribute("aria-pressed", "false");
        // A real tap right away still selects: it starts with a fresh press. The drag consumed its
        // moves, so no fling is left for Chromium (Linux, Android) to cancel with this tap.
        await touch.tap(await centre(card));
        await expect(card).toHaveAttribute("aria-pressed", "true");
        expect(moves).toEqual([]);
    });

    test("a tap right after a drop selects the next card at once", async ({page}) => {
        const moves = await routeMoves(page);
        await page.goto("/");
        const pair = page.locator('#battleCards .battle-pair[data-attack-card="6D"]');
        await expect(pair).toBeVisible();

        const touch = await touchscreen(page);
        await touch.drag(await centre(page.locator('#myHand [data-card-code="6H"]')), await centre(pair));
        await expect.poll(() => moves.map(move => move.action)).toEqual(["/defend"]);
        // The move answers at once; tap as soon as the hand is free again, well within a second.
        // The drop was a quick flick: it must not leave a fling behind that swallows this tap.
        const next = page.locator('#myHand [data-card-code="KD"]');
        await expect(next).not.toHaveAttribute("aria-disabled", "true");
        await touch.tap(await centre(next));

        await expect(next).toHaveAttribute("aria-pressed", "true");
    });

    for (const [dx, dy] of [[60, 0], [60, -20], [40, -40]]) {
        test(`on a hand that fits, a slide starting ${dx}px across and ${-dy}px up drags the card`, async ({page}) => {
            // A tablet shows the whole five-card hand: nothing to scroll sideways, so every slide drags.
            await page.setViewportSize(TABLET);
            const moves = await routeMoves(page);
            await page.goto("/");
            const pair = page.locator('#battleCards .battle-pair[data-attack-card="6D"]');
            await expect(pair).toBeVisible();

            // Straight from the press, with no lift first: the browser must not take it for a pan.
            const touch = await touchscreen(page);
            const from = await centre(page.locator('#myHand [data-card-code="6H"]'));
            await touch.down(from);
            await touch.move({x: from.x + dx, y: from.y + dy}, {steps: 6});
            await expect(page.locator(".touch-drag-ghost")).toHaveCount(1);
            await touch.move(await centre(pair));
            await touch.up();

            await expect.poll(() => moves.length).toBe(1);
            expect(moves[0]).toEqual({action: "/defend", body: {playerId: "me", attackCard: "6D", defenseCard: "6H"}});
        });
    }

    test("only a hand wider than its rail lets a sideways swipe pan, kept current as the hand and the window change", async ({page}) => {
        const long = DECK.filter(code => !["6D", "9H"].includes(code));
        let version = 7;
        let cards = sixesGame().players[0].hand;
        const sockets = [];
        await routeMoves(page, () => sixesGame({version, hand: cards}), {sockets});
        await page.goto("/");
        const hand = page.locator("#myHand");
        const first = hand.locator(".hand-card-btn").first();
        const scroller = page.locator(".hand-scroll-x");
        await expect.poll(() => sockets.length).toBeGreaterThan(0);
        const deal = async next => {
            cards = next;
            version += 1;
            sockets.at(-1).send(JSON.stringify({type: "GAME_UPDATED", version}));
            await expect(hand.locator(".hand-card-btn")).toHaveCount(next.length);
        };
        const pans = async expected => {
            expect(await scroller.evaluate(node => node.scrollWidth > node.clientWidth + 1)).toBe(expected);
            if (expected) await expect(hand).toHaveClass(/\bhand-scrolls-x\b/);
            else await expect(hand).not.toHaveClass(/\bhand-scrolls-x\b/);
            await expect(first).toHaveCSS("touch-action", expected ? "pan-x pinch-zoom" : "pinch-zoom");
        };

        await expect(hand.locator(".hand-card-btn")).toHaveCount(5);
        await pans(false);
        await deal(long.slice(0, 20));
        await pans(true);
        await deal(long.slice(0, 5));
        await pans(false);

        // Nine cards wrap on a tablet but overflow a phone's rail: rotating or resizing re-decides.
        await page.setViewportSize(TABLET);
        await deal(long.slice(0, 9));
        await pans(false);
        await page.setViewportSize(PHONE);
        await pans(true);
        await page.setViewportSize(TABLET);
        await pans(false);
    });

    test("on a hand wider than its rail, a card lifted and then slid sideways stays lifted", async ({page}) => {
        const hand = DECK.filter(code => !["6D", "9H"].includes(code)).slice(0, 20);
        const moves = await routeMoves(page, () => sixesGame({hand}));
        await page.goto("/");
        await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(20);
        await expect(page.locator("#myHand")).toHaveClass(/\bhand-scrolls-x\b/);
        const scroller = page.locator(".hand-scroll-x");
        const pair = page.locator('#battleCards .battle-pair[data-attack-card="6D"]');

        // Lifted a little (a drag), then sideways before the browser's own slop: pan-x would let the
        // browser take the slide as a pan of the hand there, so a lifted card must keep its moves.
        const touch = await touchscreen(page);
        const from = await centre(page.locator('#myHand [data-card-code="6H"]'));
        await touch.down(from);
        await touch.move({x: from.x + 2, y: from.y - 10}, {steps: 2});
        await touch.move({x: from.x + 30, y: from.y - 12}, {steps: 1});
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(1);
        await touch.move(await centre(pair));
        await touch.up();

        await expect.poll(() => moves.length).toBe(1);
        expect(moves[0]).toEqual({action: "/defend", body: {playerId: "me", attackCard: "6D", defenseCard: "6H"}});
        expect(await scroller.evaluate(node => node.scrollLeft)).toBe(0);
    });

    test("a card held still keeps its outline when a new snapshot redraws the table, and a cancelled drag lets go", async ({page}) => {
        await page.addInitScript(() => {
            // Count the frames the page asks for, and the mutation observers watching the game view.
            window.framesAsked = 0;
            const raf = window.requestAnimationFrame.bind(window);
            window.requestAnimationFrame = callback => { window.framesAsked++; return raf(callback); };
            const live = window.liveObservers = new Set();
            const {observe, disconnect} = MutationObserver.prototype;
            MutationObserver.prototype.observe = function (target, options) {
                if (target instanceof Node && document.getElementById("gameView")?.contains(target)) live.add(this);
                return observe.call(this, target, options);
            };
            MutationObserver.prototype.disconnect = function () { live.delete(this); return disconnect.call(this); };
        });
        let version = 7;
        const sockets = [];
        const moves = await routeMoves(page, () => sixesGame({version}), {sockets});
        await page.goto("/");
        const pairs = page.locator('#battleCards .battle-pair[data-attack-card="6D"]');
        await expect(pairs).toBeVisible();
        await expect.poll(() => sockets.length).toBeGreaterThan(0);
        const observers = await page.evaluate(() => window.liveObservers.size);
        // The page asks for no frames once two reads 150ms apart agree.
        const framesOnceIdle = async () => {
            let last = -1;
            await expect.poll(async () => {
                const now = await page.evaluate(() => window.framesAsked);
                const idle = now === last;
                last = now;
                return idle;
            }, {intervals: [150]}).toBe(true);
            return last;
        };
        // A newer snapshot with the same table: the pair under the finger is drawn anew.
        const redraw = async mark => {
            await pairs.evaluate((node, value) => { node.dataset.drawn = value; }, mark);
            version += 1;
            sockets.at(-1).send(JSON.stringify({type: "GAME_UPDATED", version}));
            await expect(page.locator('#battleCards .battle-pair[data-attack-card="6D"]:not([data-drawn])')).toHaveCount(1);
        };

        const touch = await touchscreen(page);
        const from = await centre(page.locator('#myHand [data-card-code="6H"]'));
        await touch.down(from);
        await touch.move({x: from.x, y: from.y - 40}, {steps: 4});
        await touch.move(await centre(pairs));
        await expect(pairs).toHaveClass(/battle-pair-drop-target/);
        await framesOnceIdle();

        // Held still while the table is redrawn: the new pair is outlined, with no frame loop left.
        await redraw("before");
        await expect(pairs).toHaveClass(/battle-pair-drop-target/);
        const idle = await framesOnceIdle();
        await page.waitForTimeout(300);
        expect(await page.evaluate(() => window.framesAsked)).toBe(idle);
        expect(await page.evaluate(() => window.liveObservers.size)).toBe(observers + 1);

        // The browser takes the touch back: nothing stays outlined, and later redraws are not watched.
        await touch.cancel();
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(0);
        await expect(page.locator("#battleCards .battle-pair-drop-target, #battleCards.battle-drop-target")).toHaveCount(0);
        expect(await page.evaluate(() => window.liveObservers.size)).toBe(observers);
        const afterCancel = await framesOnceIdle();
        await redraw("again");
        await expect(page.locator("#battleCards .battle-pair-drop-target, #battleCards.battle-drop-target")).toHaveCount(0);
        expect(await page.evaluate(() => window.framesAsked)).toBe(afterCancel);
        expect(moves).toEqual([]);
    });

    test("a card the hand rail clips is brought into view before a finger drags it", async ({page}) => {
        // Seven cards overflow a phone's rail; the only card that beats the trump queen is the last.
        const moves = await routeMoves(page, () => sixesGame({
            trumpCard: "7H",
            hand: ["6H", "8H", "9C", "9D", "9H", "10D", "KH"],
            table: [{attackCard: "QH", attackerId: "p2"}],
            legalMoves: {canDefend: true, canTake: true, defensesByAttackCard: {QH: ["KH"]}}
        }));
        await page.goto("/");
        const card = page.locator('#myHand [data-card-code="KH"]');
        const pair = page.locator('#battleCards .battle-pair[data-attack-card="QH"]');
        await expect(pair).toBeVisible();
        expect(await lands(card, await centre(card)), "KH starts clipped by the rail").toBe(false);

        await reveal(card);
        const from = await centre(card);
        expect(await lands(card, from)).toBe(true);
        const touch = await touchscreen(page);
        await touch.drag(from, await centre(pair));

        await expect.poll(() => moves.length).toBe(1);
        expect(moves[0]).toEqual({action: "/defend", body: {playerId: "me", attackCard: "QH", defenseCard: "KH"}});
    });

    test("Enter right after a cancelled drag still selects the focused card", async ({page}) => {
        const moves = await routeMoves(page);
        await page.goto("/");
        const card = page.locator('#myHand [data-card-code="7H"]');
        await expect(card).toBeVisible();
        await card.focus();

        const touch = await touchscreen(page);
        const from = await centre(card);
        await touch.down(from);
        await touch.move({x: from.x, y: from.y - 40}, {steps: 4});
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(1);
        await touch.cancel();
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(0);

        // A keyboard (or assistive) click is never the lifted finger's: it selects at once.
        await expect(card).toBeFocused();
        await page.keyboard.press("Enter");
        await expect(card).toHaveAttribute("aria-pressed", "true");
        expect(moves).toEqual([]);
    });

    test("a card the table cannot take is neither outlined nor played", async ({page}) => {
        const moves = await routeMoves(page);
        await page.goto("/");
        const row = page.locator("#battleCards");
        const pair = row.locator('.battle-pair[data-attack-card="6D"]');
        await expect(pair).toBeVisible();

        // QC beats nothing and cannot transfer.
        const touch = await touchscreen(page);
        const from = await centre(page.locator('#myHand [data-card-code="QC"]'));
        await touch.down(from);
        await touch.move({x: from.x, y: from.y - 40}, {steps: 4});
        await touch.move(await centre(pair));
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(1);
        await expect(pair).not.toHaveClass(/battle-pair-drop-target/);
        await expect(row).not.toHaveClass(/battle-drop-target/);
        await touch.up();
        await page.waitForTimeout(300);
        expect(moves).toEqual([]);
    });

    test("a tap still selects the card and plays nothing", async ({page}) => {
        const moves = await routeMoves(page);
        await page.goto("/");
        const card = page.locator('#myHand [data-card-code="7H"]');
        await expect(card).toBeVisible();

        const touch = await touchscreen(page);
        await touch.tap(await centre(card));

        await expect(card).toHaveAttribute("aria-pressed", "true");
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(0);
        await expect(page.locator("#defendBtn")).toBeEnabled();
        expect(moves).toEqual([]);
    });

    test("a sideways swipe on a long hand scrolls it, and a slide up still lifts a card", async ({page}) => {
        const hand = DECK.filter(code => !["6D", "9H"].includes(code)).slice(0, 20);
        const moves = await routeMoves(page, () => sixesGame({hand}));
        await page.goto("/");
        const scroller = page.locator(".hand-scroll-x");
        await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(20);
        expect(await scroller.evaluate(node => node.scrollWidth > node.clientWidth)).toBe(true);
        expect(await scroller.evaluate(node => node.scrollLeft)).toBe(0);

        const touch = await touchscreen(page);
        const third = await centre(page.locator("#myHand .hand-card-btn").nth(2));
        await touch.down(third);
        await touch.move({x: third.x - 160, y: third.y}, {steps: 12});
        await touch.up();
        await expect.poll(() => scroller.evaluate(node => node.scrollLeft)).toBeGreaterThan(40);
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(0);

        // The page did not scroll and no card moved; a slide up from a card still lifts it.
        const card = page.locator("#myHand .hand-card-btn").nth(6);
        const from = await centre(card);
        await touch.down(from);
        await touch.move({x: from.x, y: from.y - 40}, {steps: 4});
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(1);
        await touch.move(from);
        await touch.up();
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(0);
        expect(moves).toEqual([]);
    });

    test("while a move is pending, no other card lifts", async ({page}) => {
        let release;
        const pending = new Promise(resolve => { release = resolve; });
        const moves = await routeMoves(page, sixesGame, {hold: action => action === "/defend" ? pending : null});
        await page.goto("/");
        const pair = page.locator('#battleCards .battle-pair[data-attack-card="6D"]');
        await expect(pair).toBeVisible();

        const touch = await touchscreen(page);
        await touch.drag(await centre(page.locator('#myHand [data-card-code="6H"]')), await centre(pair));
        await expect.poll(() => moves.length).toBe(1);

        const other = page.locator('#myHand [data-card-code="7H"]');
        const from = await centre(other);
        await touch.down(from);
        await touch.move({x: from.x, y: from.y - 40}, {steps: 4});
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(0);
        await expect(other).not.toHaveClass(/dragging/);
        await touch.up();
        release();
        await page.waitForTimeout(300);
        expect(moves.map(move => move.action)).toEqual(["/defend"]);
    });

    test("a page from an earlier release drags with this script as well", async ({page}) => {
        // Byte-identical index.html of an earlier release: its import map has no touchdrag.js.
        const previous = readFileSync(new URL("./fixtures/release-21eb2c2/index.html", import.meta.url));
        await page.route(url => url.pathname === "/", route => route.fulfill({contentType: "text/html", body: previous}));
        const moves = await routeMoves(page);
        await page.goto("/");
        const pair = page.locator('#battleCards .battle-pair[data-attack-card="6D"]');
        await expect(pair).toBeVisible();
        await expect(page.locator("html")).toHaveClass(/\btouch-drag\b/);

        const touch = await touchscreen(page);
        await touch.drag(await centre(page.locator('#myHand [data-card-code="6H"]')), await centre(pair));

        await expect.poll(() => moves.length).toBe(1);
        expect(moves[0]).toEqual({action: "/defend", body: {playerId: "me", attackCard: "6D", defenseCard: "6H"}});
    });
});

/*
 * The real server and its offline bot: whatever the deal, sooner or later the player can attack
 * or beat a card. A finger plays one such card, and the server takes it.
 */
test.describe("Dragging with a finger in a real game", () => {
    test.use({hasTouch: true, isMobile: true, viewport: PHONE});

    test("a legal card slid onto the table is played", async ({page}) => {
        test.setTimeout(60_000);
        await page.goto("/");
        await page.locator("#hostName").fill("Touch Tester");
        await page.locator("#quickPlayBtn").tap();
        await expect(page.locator("#statusLabel")).toHaveText(/in progress/i);
        const touch = await touchscreen(page);
        const viewer = await page.evaluate(() => sessionStorage.getItem("durak_player_id"));

        const readGame = () => page.evaluate(async () => {
            const code = sessionStorage.getItem("durak_game_code");
            const viewer = sessionStorage.getItem("durak_player_id");
            const response = await fetch(`/api/games/${code}?viewerPlayerId=${encodeURIComponent(viewer)}`, {
                headers: {"X-Durak-Token": sessionStorage.getItem("durak_player_token")}
            });
            return response.json();
        });
        // Whether the page shows `game` as read: the same hand and table, and the hand not busy.
        const shows = game => page.evaluate(({hand, table}) => {
            const shown = [...document.querySelectorAll("#myHand .hand-card-btn")];
            return !shown.some(button => button.getAttribute("aria-disabled") === "true")
                && JSON.stringify(shown.map(button => button.dataset.cardCode).sort()) === JSON.stringify([...hand].sort())
                && JSON.stringify([...document.querySelectorAll("#battleCards .battle-pair")].map(pair => pair.dataset.attackCard)) === JSON.stringify(table);
        }, {hand: game.players.find(player => player.id === viewer).hand, table: game.table.map(pair => pair.attackCard)});
        // A hand grown past its rail (after taking cards) clips cards: bring the card and the target
        // into view, then check that both points land where they must before sliding.
        const slide = async (code, action, attackCard) => {
            const card = page.locator(`#myHand [data-card-code="${code}"]`);
            const target = page.locator(attackCard ? `#battleCards .battle-pair[data-attack-card="${attackCard}"]` : "#battleCards");
            await reveal(target);
            await reveal(card);
            const from = await centre(card);
            const box = await target.boundingBox();
            // An attack lands on the open felt at the row's corner, a defence on its pair.
            const to = attackCard ? {x: box.x + box.width / 2, y: box.y + box.height / 2} : {x: box.x + 12, y: box.y + 12};
            expect(await lands(card, from), `${code} is under the finger`).toBe(true);
            expect(await lands(attackCard ? target : page.locator(".seat.center"), to), "the finger lifts over the target").toBe(true);
            const request = page.waitForRequest(sent => sent.method() === "POST" && new URL(sent.url()).pathname.endsWith(`/${action}`));
            await touch.drag(from, to);
            const sent = await request;
            expect(sent.postDataJSON()).toMatchObject(attackCard ? {attackCard, defenseCard: code} : {card: code});
            expect((await sent.response()).ok(), `the server accepted the ${action}`).toBe(true);
            return {code, action};
        };

        let played = null;
        for (let turn = 0; turn < 12 && !played; turn++) {
            // Wait for this player's turn (an attack, a beatable card, or only Take), shown as read.
            let game;
            await expect.poll(async () => {
                game = await readGame();
                const moves = game.legalMoves || {};
                if (game.status !== "IN_PROGRESS") return true;
                return Boolean(moves.canAttack || moves.canDefend || moves.canTake) && await shows(game);
            }, {timeout: 20_000}).toBe(true);
            if (game.status !== "IN_PROGRESS") break;
            const moves = game.legalMoves;
            const defence = Object.entries(moves.defensesByAttackCard || {}).find(([, cards]) => cards.length);
            if (moves.canAttack && moves.attackableCardCodes?.length) {
                played = await slide(moves.attackableCardCodes[0], "attack");
            } else if (moves.canDefend && defence) {
                const [attackCard, [code]] = defence;
                played = await slide(code, "defend", attackCard);
            } else {
                await page.locator("#takeBtn").tap();
                await expect.poll(async () => (await readGame()).version, {timeout: 10_000}).toBeGreaterThan(game.version);
            }
        }
        expect(played, "a turn to attack or defend came up").not.toBeNull();
        // The card left the hand on the page and on the server: it was played, not just moved.
        await expect(page.locator(`#myHand [data-card-code="${played.code}"]`)).toHaveCount(0);
        const me = (await readGame()).players.find(player => player.id === viewer);
        expect(me.hand).not.toContain(played.code);
    });
});
