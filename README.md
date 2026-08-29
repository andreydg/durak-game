# Durak Game

Spring Boot multiplayer Durak game with a browser UI and websocket updates.

## Ways to play

- **Quick play** creates an invite-only two-player game against a bot and starts immediately.
- **Public rooms** appear under Open tables while waiting for players.
- **Invite-only rooms** stay out of discovery but remain joinable through their code or `?room=CODE` invite link.
- **Rematches** let the host replay a finished table with the same players, room code, and privacy setting.

## Search and language pages

The canonical public origin is `https://durak.andreyg.com`. The home page includes crawlable game/rules copy, FAQ and WebApplication structured data, social metadata, and English/Russian language alternates. Standalone guides live at `/rules.html`, `/ru.html`, and `/rules-ru.html`; `robots.txt` points crawlers to `sitemap.xml`.

Keep canonical URLs, language alternates, and sitemap entries in sync when adding pages. The frontend test suite validates that contract and the 1200×630 social preview.

## Testing

Four layers run in CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) and locally:

| Layer | Tool | Command | Covers |
| --- | --- | --- | --- |
| Backend | JUnit / Maven | `./mvnw test` | Game rules, `GameService` orchestration & autoplay, auth/tokens, concurrency, controllers + exception mapping, rate limiting, stores |
| Firestore | JUnit + emulator | `./mvnw test -Dtest=FirestoreGameStoreEmulatorTest` | Real store: transaction stale-check, codec round-trip, denormalized lobby projection (auto-skips unless `FIRESTORE_EMULATOR_HOST` is set) |
| Frontend unit | Vitest (jsdom) | `npm run test:unit` | Pure UI helpers in [`logic.js`](src/main/resources/static/js/logic.js) |
| End-to-end | Playwright | `npm run test:e2e` | Real-browser flows against the booted app (lobby discovery, quick play, private invites, gameplay, finished results/rematches, hand privacy / anti-cheat) |

First-time frontend setup:

```bash
npm ci
npx playwright install --with-deps chromium   # only needed for E2E
```

The Playwright config boots the packaged jar itself (in-memory store, offline heuristic bot — no API keys needed), so run `./mvnw -DskipTests package` once before `npm run test:e2e`.

The Firestore emulator tests run automatically in CI (against the emulator Docker image). Locally they only run when an emulator is reachable — e.g. `gcloud beta emulators firestore start --host-port=localhost:8085` then `FIRESTORE_EMULATOR_HOST=localhost:8085 ./mvnw test -Dtest=FirestoreGameStoreEmulatorTest` (needs a JDK the emulator supports).

## Security & limits

- **Per-player tokens.** Create/join returns a secret token (sent back via the `X-Durak-Token` header). The server reveals a player's hand and accepts their moves only with a matching token, so the room code alone can't read hands or spoof opponents. Games persisted before tokens fall back to accepting the player id.
- **Rate limiting.** Per-IP token buckets guard the API (`app.ratelimit.*`), with a stricter limit on game creation; the WebSocket handler caps sessions per room. Defaults are generous enough for players behind a shared NAT.
- **Health.** `/actuator/health` reports `DOWN` when the game store is unreachable; it's the platform health-check path.

## Game state storage

By default (local development), game state is stored in-memory.

When running on Cloud Run, the app automatically switches to Firestore-backed storage (detected via the `K_SERVICE` environment variable), so rooms survive instance restarts.

If your Firestore database id is not `(default)`, set:

- `FIRESTORE_DATABASE_ID` (for example `durak-store`)

Rooms use activity-based expiration: waiting lobbies expire after 30 minutes of inactivity (and always within 2 hours of entering the current lobby phase), active games after 24 hours, and finished games after 60 minutes. Returning to the lobby after a played round starts a fresh lobby phase. These values can be changed with `LOBBY_IDLE_MINUTES`, `LOBBY_MAX_AGE_MINUTES`, `ACTIVE_GAME_IDLE_HOURS`, and `FINISHED_GAME_RETENTION_MINUTES`.

The API and lobby list enforce expiration immediately. Firestore documents also carry `lastActivityAt`, `lobbyStartedAt`, and `expireAt`; configure a TTL policy on `games.expireAt` for eventual storage cleanup. The deploy script enables that policy by default because Firestore TTL deletion is asynchronous and is not used for live lobby correctness.

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
