import {describe, expect, test} from "vitest";
import {readFileSync, existsSync} from "node:fs";
import {join} from "node:path";
import {JSDOM} from "jsdom";

const staticDir = join(process.cwd(), "src/main/resources/static");
const canonicalOrigin = "https://durak.andreyg.com";
const pages = [
    ["index.html", `${canonicalOrigin}/`, "en"],
    ["ru.html", `${canonicalOrigin}/ru.html`, "ru"],
    ["rules.html", `${canonicalOrigin}/rules.html`, "en"],
    ["rules-ru.html", `${canonicalOrigin}/rules-ru.html`, "ru"]
];

function source(file) {
    return readFileSync(join(staticDir, file), "utf8");
}

function documentFor(file) {
    return new JSDOM(source(file)).window.document;
}

function structuredData(document) {
    return [...document.querySelectorAll('script[type="application/ld+json"]')]
        .map(script => JSON.parse(script.textContent));
}

describe("crawlable SEO pages", () => {
    test.each(pages)("%s has canonical, social, language, and crawler metadata", (file, canonical, language) => {
        const document = documentFor(file);
        const description = document.querySelector('meta[name="description"]')?.content || "";

        expect(document.documentElement.lang).toBe(language);
        expect(document.title.length).toBeGreaterThan(25);
        expect(description.length).toBeGreaterThan(90);
        expect(description.length).toBeLessThanOrEqual(170);
        expect(document.querySelector('meta[name="robots"]')?.content).toContain("index,follow");
        expect(document.querySelector('link[rel="canonical"]')?.href).toBe(canonical);
        expect(document.querySelector('meta[property="og:url"]')?.content).toBe(canonical);
        expect(document.querySelector('meta[property="og:image"]')?.content).toBe(`${canonicalOrigin}/social-card.png`);
        expect(document.querySelector('meta[name="twitter:card"]')?.content).toBe("summary_large_image");
        expect(document.querySelectorAll("h1")).toHaveLength(1);
        expect(document.querySelector('link[hreflang="en"]')).not.toBeNull();
        expect(document.querySelector('link[hreflang="ru"]')).not.toBeNull();
        expect(document.querySelector('link[hreflang="x-default"]')).not.toBeNull();
        expect(() => structuredData(document)).not.toThrow();
    });

    test("home page exposes useful rules and FAQ text without running JavaScript", () => {
        const document = documentFor("index.html");
        const visibleSource = document.querySelector("#lobbyView")?.textContent || "";

        expect(document.querySelector("#play-durak-online")).not.toBeNull();
        expect(document.querySelectorAll("#faq details").length).toBeGreaterThanOrEqual(4);
        expect(visibleSource).toContain("2–4 players");
        expect(visibleSource).toContain("last player");
        expect(visibleSource).toContain("no account");
    });

    test("structured data describes the free game, FAQ, and both rules guides", () => {
        const homeGraph = structuredData(documentFor("index.html"))[0]["@graph"];
        const app = homeGraph.find(item => item["@type"] === "WebApplication");
        const faq = homeGraph.find(item => item["@type"] === "FAQPage");

        expect(app.url).toBe(`${canonicalOrigin}/`);
        expect(app.isAccessibleForFree).toBe(true);
        expect(app.offers.price).toBe("0");
        expect(faq.mainEntity).toHaveLength(3);

        for (const file of ["rules.html", "rules-ru.html"]) {
            const howTo = structuredData(documentFor(file))[0];
            expect(howTo["@type"]).toBe("HowTo");
            expect(howTo.step.length).toBeGreaterThanOrEqual(5);
        }
    });

    test("robots and sitemap expose only real canonical pages", () => {
        const robots = source("robots.txt");
        expect(robots).toContain("User-agent: *");
        expect(robots).toContain(`Sitemap: ${canonicalOrigin}/sitemap.xml`);
        expect(robots).toContain("Disallow: /api/");

        const xml = new JSDOM(source("sitemap.xml"), {contentType: "text/xml"}).window.document;
        expect(xml.querySelector("parsererror")).toBeNull();
        const locations = [...xml.querySelectorAll("loc")].map(node => node.textContent.trim());
        expect(new Set(locations)).toEqual(new Set(pages.map(([, canonical]) => canonical)));
        expect(xml.querySelectorAll("url")).toHaveLength(4);

        for (const location of locations) {
            const path = new URL(location).pathname;
            const file = path === "/" ? "index.html" : path.slice(1);
            expect(existsSync(join(staticDir, file))).toBe(true);
        }
    });

    test("language alternates are reciprocal", () => {
        const pairs = [
            ["index.html", "ru.html"],
            ["rules.html", "rules-ru.html"]
        ];
        for (const [englishFile, russianFile] of pairs) {
            const english = documentFor(englishFile);
            const russian = documentFor(russianFile);
            const englishCanonical = english.querySelector('link[rel="canonical"]').href;
            const russianCanonical = russian.querySelector('link[rel="canonical"]').href;

            expect(english.querySelector('link[hreflang="ru"]').href).toBe(russianCanonical);
            expect(russian.querySelector('link[hreflang="en"]').href).toBe(englishCanonical);
        }
    });

    test("social preview is the declared 1200 by 630 PNG", () => {
        const png = readFileSync(join(staticDir, "social-card.png"));
        expect(png.subarray(1, 4).toString()).toBe("PNG");
        expect(png.readUInt32BE(16)).toBe(1200);
        expect(png.readUInt32BE(20)).toBe(630);
    });
});
