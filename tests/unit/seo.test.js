import {describe, expect, test} from "vitest";
import {readFileSync, existsSync} from "node:fs";
import {join} from "node:path";
import {createHash} from "node:crypto";
import {JSDOM} from "jsdom";

const staticDir = join(process.cwd(), "src/main/resources/static");
const canonicalOrigin = "https://durak.andreyg.com";
const pages = [
    ["index.html", `${canonicalOrigin}/`],
    ["rules.html", `${canonicalOrigin}/rules.html`]
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

function assetVersion(assetPath) {
    return createHash("sha256").update(source(assetPath)).digest("hex").slice(0, 12);
}

describe("crawlable SEO pages", () => {
    test.each(pages)("%s has canonical, social, and crawler metadata", (file, canonical) => {
        const document = documentFor(file);
        const description = document.querySelector('meta[name="description"]')?.content || "";

        expect(document.documentElement.lang).toBe("en");
        expect(document.title.length).toBeGreaterThan(25);
        expect(description.length).toBeGreaterThan(90);
        expect(description.length).toBeLessThanOrEqual(170);
        expect(document.querySelector('meta[name="robots"]')?.content).toContain("index,follow");
        expect(document.querySelector('link[rel="canonical"]')?.href).toBe(canonical);
        expect(document.querySelector('meta[property="og:url"]')?.content).toBe(canonical);
        expect(document.querySelector('meta[property="og:image"]')?.content).toBe(`${canonicalOrigin}/social-card.png`);
        expect(document.querySelector('meta[name="twitter:card"]')?.content).toBe("summary_large_image");
        expect(document.querySelectorAll("h1")).toHaveLength(1);
        expect(document.querySelector('link[hreflang="ru"]')).toBeNull();
        expect(() => structuredData(document)).not.toThrow();
    });

    test("home page exposes factual game and rules copy without JavaScript", () => {
        const document = documentFor("index.html");
        const visibleSource = document.querySelector("#lobbyView")?.textContent || "";

        expect(document.querySelector("#what-is-durak")?.textContent).toBe("What is Durak");
        expect(document.querySelector("#faq")).toBeNull();
        expect(visibleSource).toContain("Durak (Дурак) is a fast card shedding game");
        expect(visibleSource).toContain("2–4 players");
        expect(visibleSource).toContain("last player");
        expect(visibleSource).toContain("Quick Play vs Elektronik");
    });

    test.each(["index.html", "rules.html"])("%s content-versions every stylesheet and script", file => {
        const document = documentFor(file);
        const references = [
            ...document.querySelectorAll('link[rel="stylesheet"][href], script[src]')
        ].map(element => element.getAttribute(element.tagName === "LINK" ? "href" : "src"));

        expect(references.length).toBeGreaterThan(0);
        for (const reference of references) {
            const url = new URL(reference, canonicalOrigin);
            const assetPath = url.pathname.slice(1);
            expect(url.searchParams.get("v"), `${file}: ${assetPath}`).toBe(assetVersion(assetPath));
        }
    });

    test("structured data describes the English game and rules guide", () => {
        const homeGraph = structuredData(documentFor("index.html"))[0]["@graph"];
        const app = homeGraph.find(item => item["@type"] === "WebApplication");

        expect(homeGraph.some(item => item["@type"] === "FAQPage")).toBe(false);
        expect(app.url).toBe(`${canonicalOrigin}/`);
        expect(app.alternateName).toBe("Дурак");
        expect(app.inLanguage).toBe("en");
        expect(app).not.toHaveProperty("isAccessibleForFree");
        expect(app).not.toHaveProperty("offers");

        const howTo = structuredData(documentFor("rules.html"))[0];
        expect(howTo["@type"]).toBe("HowTo");
        expect(howTo.inLanguage).toBe("en");
        expect(howTo.step.length).toBeGreaterThanOrEqual(5);
    });

    test("robots and sitemap expose only the two English canonical pages", () => {
        const robots = source("robots.txt");
        expect(robots).toContain("User-agent: *");
        expect(robots).toContain(`Sitemap: ${canonicalOrigin}/sitemap.xml`);
        expect(robots).toContain("Disallow: /api/");

        const xml = new JSDOM(source("sitemap.xml"), {contentType: "text/xml"}).window.document;
        expect(xml.querySelector("parsererror")).toBeNull();
        const locations = [...xml.querySelectorAll("loc")].map(node => node.textContent.trim());
        expect(new Set(locations)).toEqual(new Set(pages.map(([, canonical]) => canonical)));
        expect(xml.querySelectorAll("url")).toHaveLength(2);

        for (const location of locations) {
            const path = new URL(location).pathname;
            const file = path === "/" ? "index.html" : path.slice(1);
            expect(existsSync(join(staticDir, file))).toBe(true);
        }
    });

    test("Russian pages and links are removed while Дурак remains in the brand", () => {
        expect(existsSync(join(staticDir, "ru.html"))).toBe(false);
        expect(existsSync(join(staticDir, "rules-ru.html"))).toBe(false);
        for (const file of ["index.html", "rules.html", "sitemap.xml"]) {
            expect(source(file)).not.toMatch(/(?:ru\.html|rules-ru|hreflang="ru")/i);
        }
        expect(source("index.html")).toContain("Durak <span>Дурак</span>");
        expect(source("rules.html")).toContain("Durak <span>Дурак</span>");
        expect(source("social-card.svg")).toContain(">Дурак</text>");
    });

    test("public copy omits advertising language and em dashes", () => {
        for (const file of ["index.html", "rules.html", "social-card.svg", "js/app.js"]) {
            expect(source(file)).not.toMatch(/\bfree\b/i);
            expect(source(file)).not.toContain(String.fromCodePoint(0x2014));
        }
        expect(source("index.html")).not.toContain("Start in seconds");
        expect(source("index.html")).not.toContain("fast Russian shedding");
    });

    test("social preview is the declared 1200 by 630 PNG", () => {
        const png = readFileSync(join(staticDir, "social-card.png"));
        expect(png.subarray(1, 4).toString()).toBe("PNG");
        expect(png.readUInt32BE(16)).toBe(1200);
        expect(png.readUInt32BE(20)).toBe(630);
    });
});
