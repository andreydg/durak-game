// Change Chromium's real default font before navigating: unlike an html style override,
// this also changes the initial font size used by em media queries.
export async function setDefaultFont(page, size) {
    const cdp = await page.context().newCDPSession(page);
    await cdp.send("Page.setFontSizes", {fontSizes: {standard: size, fixed: Math.round(size * 19 / 24)}});
    // Keep the session attached until the page closes; detaching resets the preference.
}

export async function roomCodeMetrics(page) {
    return page.locator("#gameCode").evaluate(input => {
        const style = getComputedStyle(input);
        const canvas = document.createElement("canvas");
        const context = canvas.getContext("2d");
        context.font = `${style.fontSize} ${style.fontFamily}`;
        return {
            fontSize: parseFloat(style.fontSize),
            usableWidth: input.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight),
            textWidth: context.measureText(input.value).width + parseFloat(style.letterSpacing) * input.value.length,
            scrollWidth: input.scrollWidth,
            clientWidth: input.clientWidth
        };
    });
}

export async function headerOverlaps(page) {
    return page.evaluate(() => {
        const nav = document.querySelector(".site-nav").getBoundingClientRect();
        const overlap = rect => Math.max(0, Math.min(nav.right, rect.right) - Math.max(nav.left, rect.left)) *
            Math.max(0, Math.min(nav.bottom, rect.bottom) - Math.max(nav.top, rect.top));
        const card = [...document.querySelectorAll(".lobby-card-deco .card-back-face")]
            .reduce((max, element) => Math.max(max, overlap(element.getBoundingClientRect())), 0);
        const walker = document.createTreeWalker(document.querySelector("#lobbyHeading"), NodeFilter.SHOW_TEXT);
        let heading = 0;
        while (walker.nextNode()) {
            const range = document.createRange();
            range.selectNodeContents(walker.currentNode);
            for (const rect of range.getClientRects()) heading = Math.max(heading, overlap(rect));
        }
        return {card, heading};
    });
}

export async function contentsOverflow(page) {
    return page.locator(".rules-toc a").evaluateAll(links => links.map(link => {
        const cell = link.closest("li").getBoundingClientRect();
        const range = document.createRange();
        range.selectNodeContents(link);
        return {
            label: link.textContent.trim(),
            overflow: Math.max(0, ...[...range.getClientRects()].map(rect =>
                Math.max(cell.left - rect.left, rect.right - cell.right)))
        };
    }));
}
