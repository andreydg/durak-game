import {test, expect} from "@playwright/test";

const canonicalOrigin = "https://durak.andreyg.com";

test.describe("Search discovery and rules content", () => {
    test("home page serves canonical metadata and factual crawlable copy", async ({page}) => {
        await page.goto("/");

        await expect(page).toHaveTitle("Durak Online | Card Game for 2 to 4 Players");
        await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", `${canonicalOrigin}/`);
        await expect(page.locator('meta[name="description"]')).toHaveAttribute("content", /two to four players/);
        await expect(page.locator('meta[property="og:image"]')).toHaveAttribute("content", `${canonicalOrigin}/social-card.png`);
        await expect(page.getByRole("heading", {name: "What is Durak"})).toBeVisible();
        await expect(page.locator("#faq")).toHaveCount(0);
        await expect(page.locator('link[hreflang="ru"]')).toHaveCount(0);

        const jsonLd = await page.locator('script[type="application/ld+json"]').first().textContent();
        const graph = JSON.parse(jsonLd)["@graph"];
        expect(graph.some(item => item["@type"] === "WebApplication")).toBe(true);
        expect(graph.some(item => item["@type"] === "FAQPage")).toBe(false);
        expect(graph[0]).not.toHaveProperty("isAccessibleForFree");
        expect(graph[0]).not.toHaveProperty("offers");
    });

    test("English rules page includes room, bot, privacy, and reconnect details", async ({page}) => {
        await page.goto("/rules.html");

        await expect(page).toHaveTitle(/How to Play Durak/);
        await expect(page.locator("html")).toHaveAttribute("lang", "en");
        await expect(page.getByRole("heading", {level: 1, name: "How to play Durak"})).toBeVisible();
        await expect(page.getByRole("heading", {name: "Transferring an attack"})).toBeVisible();
        await expect(page.getByRole("heading", {name: "Rooms, bots, and rematches"})).toBeVisible();
        await expect(page.locator("#online-play")).toContainText("Quick Play vs Elektronik");
        await expect(page.locator("#online-play")).toContainText("Private rooms do not appear");
        await expect(page.locator("#online-play")).toContainText("rejoin with its saved player session");
        await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", `${canonicalOrigin}/rules.html`);
        await expect(page.getByRole("link", {name: "Open the game"})).toHaveAttribute("href", "/");
    });

    test("removed Russian pages return not found", async ({request}) => {
        expect((await request.get("/ru.html")).status()).toBe(404);
        expect((await request.get("/rules-ru.html")).status()).toBe(404);
    });

    test("robots, sitemap, and social image use the English page set", async ({request}) => {
        const robots = await request.get("/robots.txt");
        expect(robots.ok()).toBe(true);
        expect(robots.headers()["content-type"]).toContain("text/plain");
        expect(await robots.text()).toContain(`Sitemap: ${canonicalOrigin}/sitemap.xml`);

        const sitemap = await request.get("/sitemap.xml");
        expect(sitemap.ok()).toBe(true);
        expect(sitemap.headers()["content-type"]).toMatch(/xml/);
        const sitemapText = await sitemap.text();
        expect(sitemapText).toContain(`${canonicalOrigin}/rules.html`);
        expect(sitemapText).not.toContain("ru.html");
        expect((sitemapText.match(/<url>/g) || [])).toHaveLength(2);

        const socialImage = await request.get("/social-card.png");
        expect(socialImage.ok()).toBe(true);
        expect(socialImage.headers()["content-type"]).toContain("image/png");
        expect((await socialImage.body()).byteLength).toBeGreaterThan(20_000);
    });
});
