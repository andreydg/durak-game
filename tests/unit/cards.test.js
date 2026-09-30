import {describe, expect, test} from "vitest";
import {existsSync, readFileSync, readdirSync} from "node:fs";
import {join} from "node:path";
import {cardImage} from "../../src/main/resources/static/js/logic.js";

// Card faces are WebP files made by scripts/convert-cards-webp.mjs from the original PNGs.
const staticDir = join(process.cwd(), "src/main/resources/static");
const DECK = ["6", "7", "8", "9", "10", "J", "Q", "K", "A"].flatMap(rank => ["C", "D", "H", "S"].map(suit => rank + suit));
const FACE_SIZE = {width: 222, height: 323};
const BACK_SIZE = {width: 314, height: 476};

/** Canvas size of a WebP file in any of its three layouts (lossy, lossless, extended). */
function webpSize(bytes) {
    if (bytes.toString("ascii", 0, 4) !== "RIFF" || bytes.toString("ascii", 8, 12) !== "WEBP") return null;
    const chunk = bytes.toString("ascii", 12, 16);
    if (chunk === "VP8X") return {width: 1 + bytes.readUIntLE(24, 3), height: 1 + bytes.readUIntLE(27, 3)};
    if (chunk === "VP8L") {
        const bits = bytes.readUInt32LE(21);
        return {width: 1 + (bits & 0x3fff), height: 1 + ((bits >>> 14) & 0x3fff)};
    }
    if (chunk === "VP8 ") return {width: bytes.readUInt16LE(26) & 0x3fff, height: bytes.readUInt16LE(28) & 0x3fff};
    return null;
}

describe("card images", () => {
    test("every card the game can show is a WebP at the original size", () => {
        for (const code of [...DECK, "BACK"]) {
            const file = join(staticDir, cardImage(code));
            expect(existsSync(file), `${code}: ${file}`).toBe(true);
            expect(webpSize(readFileSync(file)), code).toEqual(code === "BACK" ? BACK_SIZE : FACE_SIZE);
        }
    });

    test("the cards folder holds exactly the deck and the back, with no PNG left over", () => {
        const expected = [...DECK, "BACK"].map(code => `${code}.webp`).sort();
        expect(readdirSync(join(staticDir, "cards")).sort()).toEqual(expected);
    });
});
