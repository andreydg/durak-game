import {describe, expect, test} from "vitest";
import {readFileSync, readdirSync} from "node:fs";
import {join} from "node:path";
import {JSDOM} from "jsdom";
import {IMPORT_MAP_PATTERN, importMapHashSource, importMapText} from "../support/csp.js";

const staticDir = join(process.cwd(), "src/main/resources/static");
const jsFiles = readdirSync(join(staticDir, "js")).filter(file => file.endsWith(".js")).map(file => `js/${file}`);

function source(file) {
    return readFileSync(join(staticDir, file), "utf8");
}

/*
 * The pages must keep working under `script-src 'self' '<hash of the import map>';
 * style-src 'self' 'unsafe-inline'`, so they may not rely on inline script code or inline
 * event-handler attributes. The one exception is index.html's import map, which the backend
 * allows by hash (see tests/support/csp.js).
 */
describe("content security policy compatibility", () => {
    test.each(["index.html", "rules.html"])("%s has no inline executable script", file => {
        const html = source(file);
        const document = new JSDOM(html).window.document;
        for (const script of document.querySelectorAll("script")) {
            const type = script.getAttribute("type");
            if (script.hasAttribute("src")) {
                expect(new URL(script.getAttribute("src"), "https://durak.example").origin).toBe("https://durak.example");
            } else if (type === "importmap") {
                // Allowed by hash; nothing in it may point off-site.
                const map = JSON.parse(script.textContent);
                expect(Object.keys(map)).toEqual(["imports"]);
                for (const target of Object.values(map.imports)) {
                    expect(new URL(target, "https://durak.example").origin).toBe("https://durak.example");
                }
            } else {
                // JSON-LD is a data block that browsers never execute.
                expect(type).toBe("application/ld+json");
            }
        }
        const handlerAttributes = [...document.querySelectorAll("*")]
            .flatMap(element => [...element.attributes].map(attribute => attribute.name))
            .filter(name => /^on/i.test(name));
        expect(handlerAttributes).toEqual([]);
        expect(html).not.toMatch(/javascript:/i);
    });

    test("index.html has exactly one import map, in the form the backend hashes", () => {
        const html = source("index.html");
        const document = new JSDOM(html).window.document;
        expect(document.querySelectorAll('script[type="importmap"]')).toHaveLength(1);
        // The backend's regex must find that same element: same text, nothing else matched.
        expect(html.match(new RegExp(IMPORT_MAP_PATTERN.source, "g"))).toHaveLength(1);
        expect(importMapText(html)).toBe(document.querySelector('script[type="importmap"]').textContent);
        expect(importMapHashSource(html)).toMatch(/^sha256-[A-Za-z0-9+/]{43}=$/);
    });

    test("rules.html needs no script hash at all", () => {
        expect(importMapText(source("rules.html"))).toBeNull();
    });

    test.each(jsFiles)("%s does not generate inline handlers or eval code", file => {
        const code = source(file);
        expect(code).not.toMatch(/\son[a-z]+\s*=\s*["'`]/i);
        expect(code).not.toMatch(/\beval\s*\(|new Function\s*\(|setTimeout\(\s*["'`]/);
    });
});
