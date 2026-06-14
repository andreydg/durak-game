# Durak Game

Spring Boot multiplayer Durak game with a browser UI and websocket updates.

## Testing

Three layers run in CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) and locally:

| Layer | Tool | Command | Covers |
| --- | --- | --- | --- |
| Backend | JUnit / Maven | `./mvnw test` | Game rules, `GameService` orchestration & autoplay, controllers + exception mapping, stores |
| Frontend unit | Vitest (jsdom) | `npm run test:unit` | Pure UI helpers in [`logic.js`](src/main/resources/static/js/logic.js) |
| End-to-end | Playwright | `npm run test:e2e` | Real-browser flows against the booted app (lobby, add bot, start, play) |

First-time frontend setup:

```bash
npm ci
npx playwright install --with-deps chromium   # only needed for E2E
```

The Playwright config boots the packaged jar itself (in-memory store, offline heuristic bot — no API keys needed), so run `./mvnw -DskipTests package` once before `npm run test:e2e`.

## Game state storage

By default (local development), game state is stored in-memory.

When running on Cloud Run, the app automatically switches to Firestore-backed storage (detected via the `K_SERVICE` environment variable), so rooms survive instance restarts.

If your Firestore database id is not `(default)`, set:

- `FIRESTORE_DATABASE_ID` (for example `durak-store`)

Games written to Firestore include an `expireAt` timestamp set to 24 hours after game creation. Configure Firestore TTL policy on `expireAt` to let Firestore delete stale game documents automatically.

## Auto-play (Gemini)

The host can add bot players in the lobby. Bots use the primary LLM to choose moves, and every move is validated server-side. If the model is unavailable or returns invalid output, bots use a deterministic heuristic fallback.

Environment variables:

- `GEMINI_API_KEY` (empty by default; when absent, bots use heuristic fallback)
- `AUTOPLAY_GEMINI_ENABLED` (`true` by default)
- `AUTOPLAY_GEMINI_MODEL` (`gemini-3.1-flash-lite-preview` by default)
- `AUTOPLAY_GEMINI_BASE_URL` (`https://generativelanguage.googleapis.com/v1beta` by default)
- `AUTOPLAY_GEMINI_THINKING_LEVEL` (`HIGH` by default)
- `AUTOPLAY_GEMINI_REASONING_BUDGET_SECONDS` (`30` by default; prompt-level budgeted reasoning instruction for Gemma models)
- `AUTOPLAY_REQUEST_TIMEOUT_MS` (`30000` by default)

Model capability overrides (each accepts `auto`, `true`, or `false`; `auto` derives the value from the model name):

- `AUTOPLAY_GEMINI_JSON_MODE` (`auto`: enabled except for Gemma 3 models)
- `AUTOPLAY_GEMINI_SYSTEM_INSTRUCTION` (`auto`: enabled except for Gemma 3 models)
- `AUTOPLAY_GEMINI_THINKING_CONFIG` (`auto`: enabled for Gemini 3 models)
- `AUTOPLAY_GEMINI_PROMPT_REASONING_BUDGET` (`auto`: enabled for Gemma models)

API endpoint:

- `POST /api/games/{code}/bots` with body `{ "playerId": "<hostPlayerId>", "botName": "optional" }`

## Deploy to Google Cloud Run

This project already has a `Dockerfile`, so deployment uses Cloud Build + Cloud Run.

### 1) Install and authenticate gcloud

- Install the Google Cloud CLI: [https://cloud.google.com/sdk/docs/install](https://cloud.google.com/sdk/docs/install)
- Login:

```bash
gcloud auth login
```

- (Optional) If you use separate billing/account contexts:

```bash
gcloud auth application-default login
```

### 2) Deploy with one command

From repo root:

```bash
chmod +x ./scripts/deploy-cloud-run.sh
PROJECT_ID="your-project-id" REGION="us-central1" ./scripts/deploy-cloud-run.sh
```

Optional environment variables:

- `SERVICE` (default `durak-game`)
- `REPOSITORY` (default `durak-game`)
- `TAG` (default `latest`)
- `ALLOW_UNAUTHENTICATED` (default `true`)

Example:

```bash
PROJECT_ID="my-gcp-project" REGION="europe-west1" SERVICE="durak-prod" TAG="$(git rev-parse --short HEAD)" ./scripts/deploy-cloud-run.sh
```

### Single-instance deployment requirement

The deploy script pins the service to one instance (`--max-instances 1`). Keep it that way for now:

- Websocket sessions and the bot "thinking..." status live in instance memory; a second instance would split rooms across instances.
- Concurrent-write protection uses in-process per-game locks (plus a stale-version check on every Firestore save as a safety net). Multiple instances would rely on the version check alone and reject racing writes instead of serializing them.

Game state itself persists in Firestore on Cloud Run, so a restart does not lose active rooms. To scale beyond one instance later, move websocket fan-out and bot status to a shared channel (for example Firestore listeners or Pub/Sub).
