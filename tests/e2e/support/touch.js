/*
 * Real touch input for Chromium through CDP (Input.dispatchTouchEvent): the browser hit-tests,
 * applies touch-action (scroll or not) and turns the touch into pointer events exactly as for a
 * finger on a device. Playwright's touchscreen can only tap. Needs a context with hasTouch.
 */
export async function touchscreen(page) {
    const cdp = await page.context().newCDPSession(page);
    let at = null;
    const send = (type, point) => cdp.send("Input.dispatchTouchEvent", {
        type,
        touchPoints: point ? [{x: point.x, y: point.y, id: 1}] : []
    });
    return {
        async down(point) {
            at = point;
            await send("touchStart", point);
        },
        /** Slides the finger to `point` in `steps` even moves. */
        async move(point, {steps = 10} = {}) {
            const from = at;
            for (let i = 1; i <= steps; i++) {
                at = {x: from.x + (point.x - from.x) * i / steps, y: from.y + (point.y - from.y) * i / steps};
                await send("touchMove", at);
            }
        },
        async up() {
            await send("touchEnd");
            at = null;
        },
        /** Presses `from`, lifts the card straight up first as a player does, then slides to `to`. */
        async drag(from, to, {lift = 40, steps = 10} = {}) {
            await this.down(from);
            await this.move({x: from.x, y: from.y - lift}, {steps: 4});
            await this.move(to, {steps});
            await this.up();
        },
        async tap(point) {
            await this.down(point);
            await this.up();
        },
        detach: () => cdp.detach()
    };
}
