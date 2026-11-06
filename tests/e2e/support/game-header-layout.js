/** Measure text ink, not just the flex item's box: nowrap text can escape a grid column. */
export async function gameHeaderMetrics(page) {
    return page.evaluate(() => {
        const area = (a, b) => Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left)) *
            Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        const actions = [...document.querySelectorAll(".room-actions > *")]
            .filter(el => el.getClientRects().length).map(el => el.getBoundingClientRect());
        const ink = [];
        const walker = document.createTreeWalker(document.querySelector(".game-brand"), NodeFilter.SHOW_TEXT);
        while (walker.nextNode()) {
            const range = document.createRange();
            range.selectNodeContents(walker.currentNode);
            ink.push(...range.getClientRects());
        }
        const summary = [...document.querySelectorAll(".room-title-row, #roleLabel")]
            .flatMap(el => {
                const rects = [el.getBoundingClientRect()];
                const text = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
                while (text.nextNode()) {
                    const range = document.createRange();
                    range.selectNodeContents(text.currentNode);
                    rects.push(...range.getClientRects());
                }
                return rects;
            });
        return {
            brandDisplay: getComputedStyle(document.querySelector(".game-brand")).display,
            overlap: Math.max(0, ...ink.flatMap(a => actions.map(b => area(a, b)))),
            summaryOverlap: Math.max(0, ...summary.flatMap(a => actions.map(b => area(a, b)))),
            actionsOnScreen: actions.every(r => r.left >= 0 && r.right <= innerWidth && r.top >= 0 && r.bottom <= innerHeight),
            headerHeight: document.querySelector(".room-header").getBoundingClientRect().height,
            actionBottom: document.querySelector(".action-strip").getBoundingClientRect().bottom,
            viewportHeight: innerHeight,
            scrollWidth: document.documentElement.scrollWidth,
            clientWidth: document.documentElement.clientWidth
        };
    });
}
