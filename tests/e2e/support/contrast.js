import {expect} from "@playwright/test";

/**
 * Asserts WCAG AA text contrast on solid fills and returns the measured ratio.
 * Background-image gradients are not sampled: callers must use an opaque solid surface
 * or provide a conservative backdrop and guard the gradient that justifies that bound.
 * White remains the default canvas. No minimum font size is assumed.
 */
export async function expectReadableAction(locator, {backdrop = "white"} = {}) {
    await expect(locator).toBeVisible();
    const ratio = await locator.evaluate((element, backdrop) => {
        // Composite transparent action rows and translucent ancestor surfaces over the
        // supplied canvas. Canvas also normalizes CSS color syntaxes for us.
        const canvas = document.createElement("canvas");
        canvas.width = canvas.height = 1;
        const context = canvas.getContext("2d", {willReadFrequently: true});
        context.fillStyle = backdrop;
        context.fillRect(0, 0, 1, 1);
        const ancestors = [];
        for (let node = element; node; node = node.parentElement) ancestors.unshift(node);
        for (const node of ancestors) {
            context.fillStyle = getComputedStyle(node).backgroundColor;
            context.fillRect(0, 0, 1, 1);
        }
        const background = context.getImageData(0, 0, 1, 1).data;
        context.fillStyle = getComputedStyle(element).color;
        context.fillRect(0, 0, 1, 1);
        const foreground = context.getImageData(0, 0, 1, 1).data;
        const luminance = rgba => [0.2126, 0.7152, 0.0722].reduce((sum, weight, index) => {
            const value = rgba[index] / 255;
            return sum + weight * (value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
        }, 0);
        const light = Math.max(luminance(background), luminance(foreground));
        const dark = Math.min(luminance(background), luminance(foreground));
        return (light + 0.05) / (dark + 0.05);
    }, backdrop);
    expect(ratio, `${await locator.innerText()}: text/background contrast`).toBeGreaterThanOrEqual(4.5);
    return ratio;
}
