import { test, expect } from "@playwright/test";

// A tab opened before the switch to WebP keeps running its old script, which still asks for
// /cards/<code>.png whenever it draws a card. After a deploy those URLs must keep showing the card.
const DECK = ["6", "7", "8", "9", "10", "J", "Q", "K", "A"].flatMap(rank => ["C", "D", "H", "S"].map(suit => rank + suit));
const NAMES = [...DECK, "BACK"];

test.describe("Deploy compatibility", () => {
    test("card URLs used by the previous (PNG) frontend still show the card", async ({ page }) => {
        await page.goto("/");

        // Drawn the way the old frontend drew cards: an <img> pointing at the PNG path.
        const drawn = await page.evaluate(names => Promise.all(names.map(name => new Promise(resolve => {
            const img = document.createElement("img");
            img.onload = img.onerror = () => resolve({ name, width: img.naturalWidth, height: img.naturalHeight });
            img.src = `/cards/${name}.png`;
            document.body.append(img);
        }))), NAMES);

        expect(drawn).toEqual(NAMES.map(name =>
            name === "BACK" ? { name, width: 314, height: 476 } : { name, width: 222, height: 323 }));
    });
});
