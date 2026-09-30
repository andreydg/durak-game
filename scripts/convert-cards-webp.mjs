#!/usr/bin/env node
/*
 * Converts the card PNGs to WebP with Playwright's Chromium, so no cwebp or ImageMagick is needed:
 * each PNG is drawn on a same-size canvas and encoded with canvas.toBlob("image/webp", quality).
 * Dimensions and transparency are kept.
 *
 * Every card is encoded twice: lossless (quality 1) and lossy at --quality (default 0.9, with a
 * lossless alpha channel). The lossy file is kept only when it decodes to exactly the PNG's pixels
 * or is at most half the lossless size; otherwise the lossless file is written. With the current
 * art that makes the black pip cards lossy (pixel-identical), the illustrated court cards lossy
 * (about 40% of lossless, with rank and suit still crisp at 1x and 2x), and the red pip cards and
 * the back lossless: there lossy saves little and tints the edges of thin red strokes, because
 * lossy WebP stores colour at half resolution. Chromium tags each file with an sRGB ICC profile;
 * that chunk is dropped because untagged WebP is sRGB anyway.
 *
 *   node scripts/convert-cards-webp.mjs [--from <png dir>] [--out <webp dir>] [--quality 0.9]
 *   node scripts/convert-cards-webp.mjs --compare [--from <png dir>]
 *
 * --compare prints sizes and screenshot differences for several strategies: every card is shown
 * at its largest in-game size (92 CSS px wide, on white) at 1x and 2x device pixel ratio, once as
 * PNG and once as WebP, and the screenshots are compared (PSNR over all cards, the worst card, and
 * the worst rank-and-suit corner). Higher is closer; "identical" means no pixel differs.
 *
 * The PNG sources are no longer in the tree. To re-run, extract them from the commit before the
 * one that deleted them:
 *   rev=$(git log --diff-filter=D --format=%h -1 -- src/main/resources/static/cards/AS.png)
 *   mkdir -p /tmp/card-pngs && git archive "$rev^" src/main/resources/static/cards | tar -x -C /tmp/card-pngs --strip-components=4
 *   node scripts/convert-cards-webp.mjs --from /tmp/card-pngs/cards
 */
import {chromium} from "@playwright/test";
import {readFileSync, readdirSync, writeFileSync} from "node:fs";
import {basename, join} from "node:path";
import {fileURLToPath} from "node:url";

const CARDS_DIR = fileURLToPath(new URL("../src/main/resources/static/cards", import.meta.url));
const args = process.argv.slice(2);
const option = (name, fallback) => {
    const i = args.indexOf(`--${name}`);
    return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const FROM = option("from", CARDS_DIR);
const OUT = option("out", CARDS_DIR);
const QUALITY = Number(option("quality", "0.9"));
const COMPARE = args.includes("--compare");
/** Lossy is kept when it is pixel-identical or at most this fraction of the lossless size. */
const MAX_LOSSY_RATIO = 0.5;
/** Widest a card is shown in the game (hand cards), in CSS pixels. */
const CARD_CSS_WIDTH = 92;
const COMPARE_QUALITIES = [0.95, 0.9, 0.85, 0.8];

if (!(QUALITY > 0 && QUALITY <= 1)) {
    console.error("--quality must be a number in (0, 1]; 1 means lossless.");
    process.exit(1);
}

/** Removes the ICCP chunk from a WebP (RIFF) file and clears the VP8X "has ICC profile" flag. */
function withoutIccProfile(bytes) {
    const buf = Buffer.from(bytes);
    if (buf.toString("ascii", 0, 4) !== "RIFF" || buf.toString("ascii", 8, 12) !== "WEBP") return buf;
    const chunks = [];
    for (let offset = 12; offset + 8 <= buf.length;) {
        const id = buf.toString("ascii", offset, offset + 4);
        const size = buf.readUInt32LE(offset + 4);
        const end = offset + 8 + size + (size % 2);
        if (id !== "ICCP") chunks.push(Buffer.from(buf.subarray(offset, end)));
        offset = end;
    }
    const vp8x = chunks.find(chunk => chunk.toString("ascii", 0, 4) === "VP8X");
    if (vp8x) vp8x[8] &= ~0x20;
    const body = Buffer.concat([Buffer.from("WEBP"), ...chunks]);
    const header = Buffer.alloc(8);
    header.write("RIFF", 0, "ascii");
    header.writeUInt32LE(body.length, 4);
    return Buffer.concat([header, body]);
}

/**
 * Encodes one PNG in the page at each quality. Returns {bytes, identical} per quality, where
 * `identical` says the WebP decodes to exactly the PNG's pixels.
 */
async function encode(page, png, qualities) {
    const encoded = await page.evaluate(async ({b64, qualities}) => {
        const load = async src => {
            const img = new Image();
            img.src = src;
            await img.decode();
            return img;
        };
        const pixels = img => {
            const canvas = new OffscreenCanvas(img.naturalWidth, img.naturalHeight);
            const ctx = canvas.getContext("2d");
            ctx.drawImage(img, 0, 0);
            return ctx.getImageData(0, 0, canvas.width, canvas.height).data;
        };
        const img = await load(`data:image/png;base64,${b64}`);
        const original = pixels(img);
        const canvas = document.createElement("canvas");
        canvas.width = img.naturalWidth;
        canvas.height = img.naturalHeight;
        canvas.getContext("2d").drawImage(img, 0, 0);
        const out = {};
        for (const q of qualities) {
            const blob = await new Promise(resolve => canvas.toBlob(resolve, "image/webp", q));
            const url = URL.createObjectURL(blob);
            const decoded = pixels(await load(url));
            URL.revokeObjectURL(url);
            const bytes = new Uint8Array(await blob.arrayBuffer());
            let binary = "";
            for (const byte of bytes) binary += String.fromCharCode(byte);
            out[q] = {b64: btoa(binary), identical: decoded.every((value, i) => value === original[i])};
        }
        return out;
    }, {b64: png.toString("base64"), qualities});
    return Object.fromEntries(Object.entries(encoded).map(([q, result]) =>
        [q, {bytes: withoutIccProfile(Buffer.from(result.b64, "base64")), identical: result.identical}]));
}

/** Lossy where it is pixel-identical or at most `maxRatio` of the lossless size; lossless otherwise. */
function choose(encoded, quality, maxRatio = MAX_LOSSY_RATIO) {
    const lossless = encoded[1];
    const lossy = encoded[quality];
    const smaller = lossy.bytes.length < lossless.bytes.length;
    if (smaller && (lossy.identical || lossy.bytes.length <= lossless.bytes.length * maxRatio)) {
        return {bytes: lossy.bytes, mode: `lossy q=${quality}${lossy.identical ? ", pixel-identical" : ""}`};
    }
    return {bytes: lossless.bytes, mode: "lossless"};
}

const decibels = value => (Number.isFinite(value) ? `${value.toFixed(1)} dB` : "identical");

/**
 * Screenshots every card at the in-game size twice (PNG, then the candidate WebP) and compares
 * the screenshots in the page: PSNR over all cards, per card, and per rank-and-suit corner.
 */
async function screenshotDifference(browser, cards, dpr) {
    const context = await browser.newContext({deviceScaleFactor: dpr, viewport: {width: 1200, height: 800}});
    const page = await context.newPage();
    const shots = {};
    let boxes = [];
    for (const kind of ["png", "webp"]) {
        const tiles = cards.map(card => `<img style="width:${CARD_CSS_WIDTH}px" src="data:image/${kind};base64,${card[kind].toString("base64")}">`);
        await page.setContent(`<body style="margin:0;padding:4px;background:#fff;display:flex;flex-wrap:wrap;align-items:flex-start;gap:4px">${tiles.join("")}</body>`);
        await page.evaluate(() => Promise.all([...document.images].map(img => img.decode())));
        boxes = await page.$$eval("img", imgs => imgs.map(img => {
            const r = img.getBoundingClientRect();
            return [r.left, r.top, r.width, r.height];
        }));
        shots[kind] = (await page.screenshot({fullPage: true})).toString("base64");
    }
    const result = await page.evaluate(async ({a, b, boxes, dpr}) => {
        const load = async b64 => {
            const img = new Image();
            img.src = `data:image/png;base64,${b64}`;
            await img.decode();
            const canvas = new OffscreenCanvas(img.naturalWidth, img.naturalHeight);
            const ctx = canvas.getContext("2d");
            ctx.drawImage(img, 0, 0);
            return ctx.getImageData(0, 0, canvas.width, canvas.height);
        };
        const [x, y] = [await load(a), await load(b)];
        const psnr = (squared, samples) => (squared === 0 ? Infinity : 10 * Math.log10((255 * 255) / (squared / samples)));
        /** [squared error, samples] over a box given in CSS pixels. */
        const error = ([left, top, width, height]) => {
            let squared = 0;
            let samples = 0;
            for (let row = Math.round(top * dpr); row < Math.round((top + height) * dpr); row++) {
                for (let col = Math.round(left * dpr); col < Math.round((left + width) * dpr); col++) {
                    const i = (row * x.width + col) * 4;
                    for (let c = 0; c < 3; c++) squared += (x.data[i + c] - y.data[i + c]) ** 2;
                    samples += 3;
                }
            }
            return [squared, samples];
        };
        let squared = 0;
        let samples = 0;
        const cards = boxes.map(([left, top, width, height]) => {
            const [s, n] = error([left, top, width, height]);
            squared += s;
            samples += n;
            // The rank and suit sit in the top-left corner (mirrored bottom-right).
            return {card: psnr(s, n), corner: psnr(...error([left, top, width * 0.16, height * 0.22]))};
        });
        return {all: psnr(squared, samples), cards};
    }, {a: shots.png, b: shots.webp, boxes, dpr});
    await context.close();
    const worst = key => result.cards
        .map((card, i) => ({name: cards[i].name, psnr: card[key]}))
        .sort((p, q) => p.psnr - q.psnr)[0];
    return {all: result.all, card: worst("card"), corner: worst("corner")};
}

async function compare(browser, sources) {
    const pngTotal = sources.reduce((sum, s) => sum + s.png.length, 0);
    console.log(`PNG: ${pngTotal} bytes in ${sources.length} files`);
    console.log("Screenshot PSNR vs PNG, 1x | 2x: all cards / worst card / worst rank-and-suit corner");
    const strategies = [
        ["lossless", s => s.encoded[1]],
        ...COMPARE_QUALITIES.map(q => [`q=${q}, default rule`, s => choose(s.encoded, q)]),
        [`q=${QUALITY}, lossy wherever smaller`, s => choose(s.encoded, QUALITY, 1)]
    ];
    for (const [label, strategy] of strategies) {
        const cards = sources.map(s => ({name: s.name, png: s.png, webp: strategy(s).bytes}));
        const total = cards.reduce((sum, c) => sum + c.webp.length, 0);
        const columns = [];
        for (const dpr of [1, 2]) {
            const d = await screenshotDifference(browser, cards, dpr);
            columns.push(`${decibels(d.all)} / ${d.card.name} ${decibels(d.card.psnr)} / ${d.corner.name} ${decibels(d.corner.psnr)}`);
        }
        console.log(`${label.padEnd(34)} ${String(total).padStart(8)} bytes | ${columns.join(" | ")}`);
    }
}

function write(sources) {
    let before = 0;
    let after = 0;
    for (const source of sources) {
        const chosen = choose(source.encoded, QUALITY);
        writeFileSync(join(OUT, `${source.name}.webp`), chosen.bytes);
        before += source.png.length;
        after += chosen.bytes.length;
        console.log(`${source.name.padEnd(5)} ${String(source.png.length).padStart(7)} -> ${String(chosen.bytes.length).padStart(7)} bytes  ${chosen.mode}`);
    }
    console.log(`total ${before} -> ${after} bytes (${Math.round((1 - after / before) * 100)}% smaller)`);
}

const files = readdirSync(FROM).filter(name => name.endsWith(".png")).sort();
if (!files.length) {
    console.error(`No PNG files in ${FROM}.`);
    process.exit(1);
}
const browser = await chromium.launch();
try {
    const page = await browser.newPage();
    const qualities = [...new Set(COMPARE ? [1, ...COMPARE_QUALITIES, QUALITY] : [1, QUALITY])];
    const sources = [];
    for (const file of files) {
        const png = readFileSync(join(FROM, file));
        sources.push({name: basename(file, ".png"), png, encoded: await encode(page, png, qualities)});
    }
    if (COMPARE) await compare(browser, sources);
    else write(sources);
} finally {
    await browser.close();
}
