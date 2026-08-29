import {test, expect} from "@playwright/test";

const canonicalOrigin = "https://durak.andreyg.com";

test.describe("Search discovery and rules content", () => {
    test("home page serves canonical metadata and crawlable game copy", async ({page}) => {
        await page.goto("/");

        await expect(page).toHaveTitle("Play Durak Online – Free Multiplayer Card Game");
        await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", `${canonicalOrigin}/`);
        await expect(page.locator('meta[name="description"]')).toHaveAttribute("content", /2–4 players/);
        await expect(page.locator('meta[property="og:image"]')).toHaveAttribute("content", `${canonicalOrigin}/social-card.png`);
        await expect(page.getByRole("heading", {name: "Play Durak online with friends or a bot"})).toBeVisible();
        await expect(page.locator("#faq details")).toHaveCount(4);

        const jsonLd = await page.locator('script[type="application/ld+json"]').first().textContent();
        const graph = JSON.parse(jsonLd)["@graph"];
        expect(graph.some(item => item["@type"] === "WebApplication")).toBe(true);
        expect(graph.some(item => item["@type"] === "FAQPage")).toBe(true);
    });

    test("English rules page is navigable and links back to the game", async ({page}) => {
        await page.goto("/rules.html");

        await expect(page).toHaveTitle(/How to Play Durak/);
        await expect(page.locator("html")).toHaveAttribute("lang", "en");
        await expect(page.getByRole("heading", {level: 1, name: "How to play Durak"})).toBeVisible();
        await expect(page.getByRole("heading", {name: "Transferring an attack"})).toBeVisible();
        await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", `${canonicalOrigin}/rules.html`);
        await expect(page.getByRole("link", {name: "Play Durak free"})).toHaveAttribute("href", "/");
    });

    test("Russian landing and rules pages declare reciprocal language versions", async ({page}) => {
        await page.goto("/ru.html");
        await expect(page.locator("html")).toHaveAttribute("lang", "ru");
        await expect(page.getByRole("heading", {level: 1, name: "Дурак онлайн"})).toBeVisible();
        await expect(page.locator('link[hreflang="en"]')).toHaveAttribute("href", `${canonicalOrigin}/`);
        await expect(page.locator('link[hreflang="ru"]')).toHaveAttribute("href", `${canonicalOrigin}/ru.html`);

        await page.goto("/rules-ru.html");
        await expect(page.getByRole("heading", {level: 1, name: "Как играть в Дурака"})).toBeVisible();
        await expect(page.getByRole("heading", {name: "Перевод атаки"})).toBeVisible();
        await expect(page.locator('link[hreflang="en"]')).toHaveAttribute("href", `${canonicalOrigin}/rules.html`);
    });

    test("robots, sitemap, and social image are served with crawler-friendly types", async ({request}) => {
        const robots = await request.get("/robots.txt");
        expect(robots.ok()).toBe(true);
        expect(robots.headers()["content-type"]).toContain("text/plain");
        expect(await robots.text()).toContain(`Sitemap: ${canonicalOrigin}/sitemap.xml`);

        const sitemap = await request.get("/sitemap.xml");
        expect(sitemap.ok()).toBe(true);
        expect(sitemap.headers()["content-type"]).toMatch(/xml/);
        const sitemapText = await sitemap.text();
        expect(sitemapText).toContain(`${canonicalOrigin}/rules-ru.html`);
        expect((sitemapText.match(/<url>/g) || [])).toHaveLength(4);

        const socialImage = await request.get("/social-card.png");
        expect(socialImage.ok()).toBe(true);
        expect(socialImage.headers()["content-type"]).toContain("image/png");
        expect((await socialImage.body()).byteLength).toBeGreaterThan(20_000);
    });
});
