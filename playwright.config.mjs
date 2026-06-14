import { defineConfig, devices } from "@playwright/test";

const PORT = process.env.E2E_PORT || "8080";
const BASE_URL = `http://localhost:${PORT}`;
const JAR = "target/durak-game-0.0.1-SNAPSHOT.jar";

export default defineConfig({
    testDir: "tests/e2e",
    fullyParallel: false,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 1 : 0,
    workers: 1,
    reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
    timeout: 30_000,
    expect: { timeout: 10_000 },
    use: {
        baseURL: BASE_URL,
        trace: "on-first-retry"
    },
    projects: [
        { name: "chromium", use: { ...devices["Desktop Chrome"] } }
    ],
    // Boots the real Spring Boot app (in-memory store, offline heuristic bot — no API keys needed).
    webServer: {
        command: `java -jar ${JAR}`,
        url: BASE_URL,
        timeout: 120_000,
        reuseExistingServer: !process.env.CI,
        env: {
            PORT,
            AUTOPLAY_GEMINI_ENABLED: "false"
        }
    }
});
