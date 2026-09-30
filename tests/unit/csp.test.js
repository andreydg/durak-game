import {describe, expect, test} from "vitest";
import {readFileSync} from "node:fs";
import {join} from "node:path";
import {JSDOM} from "jsdom";

const staticDir = join(process.cwd(), "src/main/resources/static");

function source(file) {
    return readFileSync(join(staticDir, file), "utf8");
}

/*
 * The pages must keep working under `script-src 'self'; style-src 'self' 'unsafe-inline'`,
 * so they may not rely on inline script code or inline event-handler attributes.
 */
describe("content security policy compatibility", () => {
    test.each(["index.html", "rules.html"])("%s has no inline executable script", file => {
        const document = new JSDOM(source(file)).window.document;
        for (const script of document.querySelectorAll("script")) {
            if (script.hasAttribute("src")) {
                expect(new URL(script.getAttribute("src"), "https://durak.example").origin).toBe("https://durak.example");
            } else {
                // JSON-LD is a data block that browsers never execute.
                expect(script.getAttribute("type")).toBe("application/ld+json");
            }
        }
        const handlerAttributes = [...document.querySelectorAll("*")]
            .flatMap(element => [...element.attributes].map(attribute => attribute.name))
            .filter(name => /^on/i.test(name));
        expect(handlerAttributes).toEqual([]);
        expect(source(file)).not.toMatch(/javascript:/i);
    });

    test.each(["js/app.js", "js/logic.js"])("%s does not generate inline handlers or eval code", file => {
        const code = source(file);
        expect(code).not.toMatch(/\son[a-z]+\s*=\s*["'`]/i);
        expect(code).not.toMatch(/\beval\s*\(|new Function\s*\(|setTimeout\(\s*["'`]/);
    });
});
