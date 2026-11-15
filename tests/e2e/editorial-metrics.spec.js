import {test, expect} from "@playwright/test";
import {roomCodeMetrics} from "./support/editorial-layout.js";

// Exercise the measurement independently of the landing layout. In particular,
// a browser upgrade must not double-subtract padding or hide real clipping.
for (const boxSizing of ["border-box", "content-box"]) {
    test(`room-code editing width excludes borders and padding once (${boxSizing})`, async ({page}) => {
        await page.setContent(`<input id="gameCode" value="WWWWWW" style="
            box-sizing: ${boxSizing}; width: 200px; font: 16px monospace; letter-spacing: 1px;
            border: solid black; border-width: 2px 5px 2px 3px; padding: 7px 17px 7px 11px;">`);
        const metrics = await roomCodeMetrics(page);
        expect(metrics.usableWidth).toBeCloseTo(boxSizing === "border-box" ? 164 : 200, 5);
        expect(metrics.textWidth).toBeLessThan(metrics.usableWidth);
        expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth);
    });
}

test("room-code metrics still detect a genuinely clipped six-character code", async ({page}) => {
    await page.setContent(`<input id="gameCode" value="WWWWWW" style="
        box-sizing: border-box; width: 60px; font: 16px monospace; letter-spacing: 1px;
        border: 2px solid black; padding: 7px 10px;">`);
    const metrics = await roomCodeMetrics(page);
    expect(metrics.usableWidth).toBeCloseTo(36, 5);
    expect(metrics.textWidth).toBeGreaterThan(metrics.usableWidth);
    expect(metrics.scrollWidth).toBeGreaterThan(metrics.clientWidth);
});
