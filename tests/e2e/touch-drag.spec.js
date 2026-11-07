import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import { DECK, seedSession } from "./support/synthetic.js";
import { centre, routeMoves, sixesGame } from "./support/table-moves.js";
import { lands, reveal, touchscreen } from "./support/touch.js";

const PHONE = {width: 390, height: 844};
const TABLET = {width: 820, height: 1180}; // an iPad in portrait

/**
 * Counts the frames the page asks for (window.framesAsked), the mutation observers watching the
 * game view (window.liveObservers), and the live pointer and touchmove listeners the app's own
 * modules added (window.liveListeners; Playwright adds some of its own), so a test can tell that
 * a drag left nothing running behind it.
 */
async function watchDragLifetime(page) {
    await page.addInitScript(() => {
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
        const listeners = window.liveListeners = [];
        const types = new Set(["pointermove", "pointerup", "pointercancel", "pointerdown", "touchmove"]);
        const {addEventListener: add, removeEventListener: remove} = EventTarget.prototype;
        const capture = options => Boolean(typeof options === "object" ? options?.capture : options);
        // A test's own probe listeners are not the page's.
        window.untracked = {add: (target, type, listener) => add.call(target, type, listener)};
        EventTarget.prototype.addEventListener = function (type, listener, options) {
            const fromApp = types.has(type) && /\/js\/[\w-]+\.js/.test(new Error().stack);
            if (fromApp && !listeners.some(l => l.target === this && l.type === type && l.listener === listener && l.capture === capture(options))) {
                listeners.push({target: this, type, listener, capture: capture(options)});
            }
            return add.call(this, type, listener, options);
        };
        EventTarget.prototype.removeEventListener = function (type, listener, options) {
            const at = listeners.findIndex(l => l.target === this && l.type === type && l.listener === listener && l.capture === capture(options));
            if (at >= 0) listeners.splice(at, 1);
            return remove.call(this, type, listener, options);
        };
    });
}

/** The frame count once the page has stopped asking for frames: two reads 150ms apart agree. */
async function framesOnceIdle(page) {
    let last = -1;
    await expect.poll(async () => {
        const now = await page.evaluate(() => window.framesAsked);
        const idle = now === last;
        last = now;
        return idle;
    }, {intervals: [150]}).toBe(true);
    return last;
}

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
        await watchDragLifetime(page);
        let version = 7;
        const sockets = [];
        const moves = await routeMoves(page, () => sixesGame({version}), {sockets});
        await page.goto("/");
        const pairs = page.locator('#battleCards .battle-pair[data-attack-card="6D"]');
        await expect(pairs).toBeVisible();
        await expect.poll(() => sockets.length).toBeGreaterThan(0);
        const observers = await page.evaluate(() => window.liveObservers.size);
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
        await framesOnceIdle(page);

        // Held still while the table is redrawn: the new pair is outlined, with no frame loop left.
        await redraw("before");
        await expect(pairs).toHaveClass(/battle-pair-drop-target/);
        const idle = await framesOnceIdle(page);
        await page.waitForTimeout(300);
        expect(await page.evaluate(() => window.framesAsked)).toBe(idle);
        expect(await page.evaluate(() => window.liveObservers.size)).toBe(observers + 1);

        // The browser takes the touch back: nothing stays outlined, and later redraws are not watched.
        await touch.cancel();
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(0);
        await expect(page.locator("#battleCards .battle-pair-drop-target, #battleCards.battle-drop-target")).toHaveCount(0);
        expect(await page.evaluate(() => window.liveObservers.size)).toBe(observers);
        const afterCancel = await framesOnceIdle(page);
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

    test("while a move is pending, a flick on another card scrolls the page, and a tap right after selects", async ({page}) => {
        await page.setViewportSize({width: 390, height: 640});
        let release;
        const pending = new Promise(resolve => { release = resolve; });
        let current = sixesGame();
        const moves = await routeMoves(page, () => current, {hold: action => action === "/defend" ? pending : null});
        await page.goto("/");
        const card = page.locator('#myHand [data-card-code="6H"]');
        await expect(card).toBeVisible();
        // Defend the 6D with the 7H through the buttons; the answer is held.
        await page.locator('#myHand [data-card-code="7H"]').tap();
        await page.locator("#defendBtn").tap();
        await expect.poll(() => moves.map(move => move.action)).toEqual(["/defend"]);
        await expect(card).toHaveAttribute("aria-disabled", "true");

        // A card that can't be dragged keeps the browser's own gestures, as without touch dragging:
        // a flick that starts on it scrolls the page.
        await expect(card).toHaveCSS("touch-action", "manipulation");
        const touch = await touchscreen(page);
        const before = await page.evaluate(() => window.scrollY);
        const from = await centre(card);
        await touch.down(from);
        await touch.move({x: from.x + 90, y: from.y - 120}, {steps: 12});
        await touch.up();
        await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(before);
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(0);

        // The defence is accepted: the 7H is on the table and the hand is free again. A tap right
        // away selects: no invisible fling is left for Chromium (Linux, Android) to cancel with it.
        current = sixesGame({
            version: 8, hand: ["6H", "JH", "QC", "KD"], legalMoves: {},
            table: [{attackCard: "6D", defenseCard: "7H", attackerId: "p2"}]
        });
        release();
        await expect(card).not.toHaveAttribute("aria-disabled", "true");
        await reveal(card);
        await touch.tap(await centre(card));
        await expect(card).toHaveAttribute("aria-pressed", "true");
        expect(moves.map(move => move.action)).toEqual(["/defend"]);
    });

    test("a card that can't be dragged any more once the slide starts lets the slide go without a fling", async ({page}) => {
        let release;
        const pending = new Promise(resolve => { release = resolve; });
        let current = sixesGame();
        const moves = await routeMoves(page, () => current, {hold: action => action === "/defend" ? pending : null});
        await page.goto("/");
        const card = page.locator('#myHand [data-card-code="6H"]');
        await expect(card).toBeVisible();
        // Select the 7H with the keyboard, so Defend is ready for it.
        await page.locator('#myHand [data-card-code="7H"]').focus();
        await page.keyboard.press("Enter");
        await expect(page.locator('#myHand [data-card-code="7H"]')).toHaveAttribute("aria-pressed", "true");

        // A finger lands on the 6H while it can still be dragged, so the touch can't pan the page.
        const touch = await touchscreen(page);
        const from = await centre(card);
        await touch.down(from);
        // A probe at the end of the moves' path (the card is still in the hand, so they bubble to
        // the window) sees whether anything took each one once the card became undraggable.
        await card.evaluate(button => {
            window.movesAfterBusy = [];
            window.addEventListener("touchmove", event => {
                if (button.getAttribute("aria-disabled") === "true") window.movesAfterBusy.push(event.defaultPrevented);
            });
        });
        // Defend starts (another input) before the finger moves: the hand can't be dragged now.
        await page.locator("#defendBtn").evaluate(button => button.click());
        await expect(card).toHaveAttribute("aria-disabled", "true");
        // The slide doesn't lift the card, and its moves are still taken: no pan, no fling.
        await touch.move({x: from.x, y: from.y - 40}, {steps: 4});
        await touch.move({x: from.x + 100, y: from.y - 95}, {steps: 10});
        await touch.up();
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(0);
        // Chromium holds back a move still inside its touch slop, so count only what arrived.
        const busyMoves = await page.evaluate(() => window.movesAfterBusy);
        expect(busyMoves.length).toBeGreaterThan(5);
        expect(busyMoves).not.toContain(false);

        current = sixesGame({
            version: 8, hand: ["6H", "JH", "QC", "KD"], legalMoves: {},
            table: [{attackCard: "6D", defenseCard: "7H", attackerId: "p2"}]
        });
        release();
        await expect(card).not.toHaveAttribute("aria-disabled", "true");
        await touch.tap(await centre(card));
        await expect(card).toHaveAttribute("aria-pressed", "true");
        expect(moves.map(move => move.action)).toEqual(["/defend"]);
    });

    test("a card a refresh takes from the hand mid-drag lets go at once, and its touch can't swallow the next tap", async ({page}) => {
        await watchDragLifetime(page);
        let played = false;
        const sockets = [];
        // The same seat plays the 6H from another tab: the next snapshot has it on the table.
        const moves = await routeMoves(page, () => (played
            ? sixesGame({version: 8, hand: ["7H", "JH", "QC", "KD"], legalMoves: {},
                table: [{attackCard: "6D", defenseCard: "6H", attackerId: "p2"}]})
            : sixesGame()), {sockets});
        await page.goto("/");
        const card = page.locator('#myHand [data-card-code="6H"]');
        await expect(card).toBeVisible();
        await expect.poll(() => sockets.length).toBeGreaterThan(0);
        const observers = await page.evaluate(() => window.liveObservers.size);
        const listeners = await page.evaluate(() => window.liveListeners.length);

        const touch = await touchscreen(page);
        const from = await centre(card);
        await touch.down(from);
        await touch.move({x: from.x, y: from.y - 40}, {steps: 4});
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(1);
        // A probe on the card itself, added after the page's own listeners, sees whether each move
        // was taken once the card is out of the page (its touches no longer reach #myHand).
        await card.evaluate(button => {
            window.movesAfterRemoval = [];
            window.untracked.add(button, "touchmove", event => {
                if (!button.isConnected) window.movesAfterRemoval.push(event.defaultPrevented);
            });
        });
        played = true;
        sockets.at(-1).send(JSON.stringify({type: "GAME_UPDATED", version: 8}));
        await expect(card).toHaveCount(0);

        // With the finger still down, the drag lets go at once: no ghost, outline, observer or frames.
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(0);
        await expect(page.locator("html.touch-dragging, .dragging")).toHaveCount(0);
        await expect(page.locator("#battleCards .battle-pair-drop-target, #battleCards.battle-drop-target")).toHaveCount(0);
        expect(await page.evaluate(() => window.liveObservers.size)).toBe(observers);
        const idle = await framesOnceIdle(page);

        // The finger keeps sliding and flicks off. Those moves still belong to the drag: the browser
        // neither pans nor flings with them, so the next tap selects at once.
        await touch.move({x: from.x + 110, y: from.y - 95}, {steps: 10});
        await touch.up();
        const removedMoves = await page.evaluate(() => window.movesAfterRemoval);
        expect(removedMoves.length).toBeGreaterThan(5);
        expect(removedMoves).not.toContain(false);
        expect(await page.evaluate(() => window.framesAsked)).toBe(idle);
        const next = page.locator('#myHand [data-card-code="7H"]');
        await touch.tap(await centre(next));
        await expect(next).toHaveAttribute("aria-pressed", "true");
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(0);
        expect(await page.evaluate(() => window.liveListeners.length)).toBe(listeners);
        expect(moves).toEqual([]);
    });

    test("a hand that grows past its rail under a held finger keeps the touch it began with: a sideways slide still drags", async ({page}) => {
        // A defender holds a card while the bout ends and the table's cards come back: five become nine.
        let hand = ["6H", "7H", "JH", "QC", "KD"];
        let version = 7;
        const sockets = [];
        const moves = await routeMoves(page, () => sixesGame({version, hand}), {sockets});
        await page.goto("/");
        const card = page.locator('#myHand [data-card-code="6H"]');
        const rail = page.locator(".hand-scroll-x");
        await expect(card).toBeVisible();
        await expect.poll(() => sockets.length).toBeGreaterThan(0);
        // The hand fits, so the browser may not pan this touch: every slide is the page's to handle.
        await expect(card).toHaveCSS("touch-action", "pinch-zoom");

        const touch = await touchscreen(page);
        const from = await centre(card);
        await touch.down(from);
        await card.evaluate(button => {
            window.heldCard = button;
            window.movesWhileLifted = [];
            window.addEventListener("touchmove", event => {
                if (document.querySelector(".touch-drag-ghost")) window.movesWhileLifted.push(event.defaultPrevented);
            });
        });
        hand = [...hand, "8C", "9C", "10C", "AC"];
        version = 8;
        sockets.at(-1).send(JSON.stringify({type: "GAME_UPDATED", version}));
        await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(9);
        // A new touch on this overflowing hand could pan sideways; the one already down still can't.
        await expect(card).toHaveCSS("touch-action", "pan-x pinch-zoom");
        expect(await card.evaluate(button => button === window.heldCard)).toBe(true);

        const left = await rail.evaluate(node => node.scrollLeft);
        await touch.move({x: from.x + 100, y: from.y}, {steps: 12});
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(1);
        expect(await rail.evaluate(node => node.scrollLeft)).toBe(left);
        const lifted = await page.evaluate(() => window.movesWhileLifted);
        expect(lifted.length).toBeGreaterThan(5);
        expect(lifted).not.toContain(false);

        // Lifted over the hand: nothing is played. The next tap and drag work at once.
        await touch.up();
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(0);
        const next = page.locator('#myHand [data-card-code="7H"]');
        await reveal(next);
        await touch.tap(await centre(next));
        await expect(next).toHaveAttribute("aria-pressed", "true");
        const again = await centre(next);
        await touch.down(again);
        await touch.move({x: again.x, y: again.y - 40}, {steps: 4});
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(1);
        await touch.cancel();
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(0);
        expect(moves).toEqual([]);
    });

    test("a hand that shrinks to fit under a held finger leaves its sideways swipe to the browser", async ({page}) => {
        let hand = ["6H", "7H", "8C", "9C", "10C", "JH", "QC", "KD", "AC"];
        let version = 7;
        const sockets = [];
        const moves = await routeMoves(page, () => sixesGame({version, hand}), {sockets});
        await page.goto("/");
        const card = page.locator('#myHand [data-card-code="6H"]');
        await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(9);
        await expect.poll(() => sockets.length).toBeGreaterThan(0);
        // The hand overflows, so this touch may pan sideways: a sideways swipe belongs to the browser.
        await expect(card).toHaveCSS("touch-action", "pan-x pinch-zoom");

        const touch = await touchscreen(page);
        const from = await centre(card);
        await touch.down(from);
        await card.evaluate(button => {
            window.heldCard = button;
            window.movesAfterShrink = [];
            window.addEventListener("touchmove", event => window.movesAfterShrink.push(event.defaultPrevented));
        });
        hand = ["6H", "7H", "JH", "QC", "KD"];
        version = 8;
        sockets.at(-1).send(JSON.stringify({type: "GAME_UPDATED", version}));
        await expect(page.locator("#myHand .hand-card-btn")).toHaveCount(5);
        await expect(card).toHaveCSS("touch-action", "pinch-zoom");
        expect(await card.evaluate(button => button === window.heldCard)).toBe(true);

        // Still the browser's swipe, though nothing is left to scroll: no card lifts, no move is taken.
        await touch.move({x: from.x + 100, y: from.y}, {steps: 12});
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(0);
        const taken = await page.evaluate(() => window.movesAfterShrink);
        expect(taken.length).toBeGreaterThan(5);
        expect(taken).not.toContain(true);
        await touch.up();

        // With nothing to scroll there's no fling to stop: the next tap and drag work at once.
        const next = page.locator('#myHand [data-card-code="7H"]');
        await touch.tap(await centre(next));
        await expect(next).toHaveAttribute("aria-pressed", "true");
        const again = await centre(next);
        await touch.down(again);
        await touch.move({x: again.x, y: again.y - 40}, {steps: 4});
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(1);
        await touch.cancel();
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(0);
        expect(moves).toEqual([]);
    });

    test("a second finger lets go of the card, and the two fingers pinch the page", async ({page}) => {
        const moves = await routeMoves(page);
        await page.goto("/");
        const card = page.locator('#myHand [data-card-code="6H"]');
        await expect(card).toBeVisible();
        const cdp = await page.context().newCDPSession(page);
        const send = (type, touchPoints) => cdp.send("Input.dispatchTouchEvent", {type, touchPoints});
        const from = await centre(card);
        let first = {x: from.x, y: from.y, id: 1};
        await send("touchStart", [first]);
        for (let i = 1; i <= 4; i++) await send("touchMove", [{...first, y: from.y - 10 * i}]);
        first = {...first, y: from.y - 40};
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(1);

        const second = {x: Math.min(first.x + 100, PHONE.width - 20), y: first.y, id: 2};
        await send("touchStart", [first, second]);
        await expect(page.locator(".touch-drag-ghost")).toHaveCount(0);
        await expect(card).not.toHaveClass(/dragging/);
        const scale = await page.evaluate(() => window.visualViewport.scale);
        for (let i = 1; i <= 10; i++) {
            await send("touchMove", [{...first, x: first.x - 3 * i}, {...second, x: second.x + 5 * i}]);
        }
        await send("touchEnd", []);
        await expect.poll(() => page.evaluate(() => window.visualViewport.scale)).toBeGreaterThan(scale);
        expect(moves).toEqual([]);
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
