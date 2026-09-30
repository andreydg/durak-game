# Durak Game

Spring Boot multiplayer Durak game with a browser UI and websocket updates.

## Ways to play

- **Quick play** creates an invite-only two-player game against a bot and starts immediately.
- **Public rooms** appear under Open tables while waiting for players.
- **Invite-only rooms** stay out of discovery but remain joinable through their code or `?room=CODE` invite link.
- **Rematches** let the host replay a finished table with the same players, room code, and privacy setting.

## Search pages

The canonical public origin is `https://durak.andreyg.com`. The home page includes crawlable game and rules copy, WebApplication structured data, and social metadata. The standalone guide lives at `/rules.html`; `robots.txt` points crawlers to `sitemap.xml`.

Keep canonical URLs and sitemap entries in sync when adding pages. The frontend test suite validates that contract and the 1200×630 social preview.

## Frontend

The browser UI in [`src/main/resources/static`](src/main/resources/static) is plain HTML, CSS and native ES modules, with no bundler or build step. The modules in `js/`:

- `main.js`: entry point; wires up events, starts the page, and assigns the few test hooks the Playwright specs call.
- `logic.js`: pure, unit-tested helpers without DOM access (game-state questions, labels, hints, layout maths).
- `state.js` and `dom.js`: shared UI state and element lookups.
- `api.js`: the saved seat and authenticated JSON requests. `socket.js`: one reconnecting WebSocket helper used by both channels.
- `sync.js`: game socket, fallback reads and heartbeats. `lobby.js`: the Open tables list and its socket.
- `actions.js`: player actions and session changes. `view.js`: rendering, focus, alerts and announcements.

**Asset versions.** Every CSS and JS URL carries `?v=` plus the first 12 hex digits of the file's SHA-256, and static files are served `no-cache`, so browsers revalidate and pick up changes at once. Modules import each other with relative specifiers (`./logic.js`); an inline `<script type="importmap">` at the end of `index.html` maps each module to its versioned URL, followed by `modulepreload` links and the versioned entry script. After changing any CSS or JS file, run `node scripts/update-asset-versions.mjs` to recompute the hashes and regenerate that block; the unit tests fail while a hash is stale. The Content-Security-Policy allows the import map by the SHA-256 of the exact text between `<script type="importmap">` and `</script>` (see [`tests/support/csp.js`](tests/support/csp.js)), so keep that tag exactly as written and add no other inline script.

**Card images.** The card faces and back in `cards/` are WebP files made from the original PNGs by [`scripts/convert-cards-webp.mjs`](scripts/convert-cards-webp.mjs) with Playwright's Chromium, at the original size. Cards are lossy (quality 0.9) where that is pixel-identical or at most half the lossless size (court cards, black pip cards) and lossless elsewhere; `--compare` prints sizes and 1x/2x screenshot differences for the alternatives. The script's header explains how to restore the PNGs from git history to re-run it.

## Testing

Four layers run in CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) and locally:

| Layer | Tool | Command | Covers |
| --- | --- | --- | --- |
| Backend | JUnit / Maven | `./mvnw test` | Game rules (including a seeded rules fuzzer), `GameService` orchestration & autoplay, auth/tokens, concurrency, controllers + exception mapping, rate limiting, security headers, stores |
| Firestore | JUnit + emulator | `./mvnw test -Dtest=FirestoreGameStoreEmulatorTest` | Real store: transaction stale-check, codec round-trip, denormalized lobby projection (auto-skips unless `FIRESTORE_EMULATOR_HOST` is set) |
| Frontend unit | Vitest (jsdom) | `npm run test:unit` | Pure UI helpers in [`logic.js`](src/main/resources/static/js/logic.js), plus static-page contracts: search metadata, versioned assets and the import map, CSP-safe markup, card images |
| End-to-end | Playwright | `npm run test:e2e` | Real-browser flows against the booted app (lobby discovery, quick play, private invites, gameplay, finished results/rematches, hand privacy / anti-cheat), plus UI behaviour with synthetic games: phone/desktop layout (no horizontal overflow, hand above the sticky action strip), double-submit guards, keyboard focus, screen-reader names and announcements, leave confirmation, reconnecting after reload, API error handling, saved-seat validity, and running under a strict CSP |

First-time frontend setup:

```bash
npm ci
npx playwright install --with-deps chromium   # only needed for E2E
```

The Playwright config boots the packaged jar itself (in-memory store and offline heuristic bot; no API keys needed), so run `./mvnw -DskipTests package` once before `npm run test:e2e`.

`DurakRulesFuzzTest` plays seeded random games through the rules engine, checking card conservation, legal beats, bout limits, roles and results after every action, and fires illegal "probe" actions that must leave the game unchanged. CI runs a few-second sweep; for a deep one run `./mvnw test -Dtest=DurakRulesFuzzTest -Dfuzz.games=4000 -Dfuzz.seed=424242` (reports land in `target/fuzz-reports/`).

The Firestore emulator tests run automatically in CI (against the emulator Docker image). Locally they only run when an emulator is reachable. For example, run `gcloud beta emulators firestore start --host-port=localhost:8085` then `FIRESTORE_EMULATOR_HOST=localhost:8085 ./mvnw test -Dtest=FirestoreGameStoreEmulatorTest` (needs a JDK the emulator supports).

## Security & limits

- **Per-player tokens.** Create/join returns a secret token (sent back via the `X-Durak-Token` header). The server reveals a player's hand and accepts their moves only with a matching token, so the room code alone can't read hands or spoof opponents. The (public) player id is never accepted as a token. Rejected tokens are logged as `auth_rejected` (seat and reason, never the token), at most once a minute per seat.
- **Rate limiting.** Per-client token buckets guard the API (`app.ratelimit.*`), with a stricter limit on game creation; game and lobby WebSocket handlers cap their connection sets. Defaults are generous enough for players behind a shared NAT. The client is the `X-Forwarded-For` entry appended by the trusted proxy, counted from the right (`app.ratelimit.forwarded-for-hops`: `1` for Cloud Run, `2` behind an external load balancer); entries further left are client-supplied and ignored. IPv6 clients are bucketed per /64. This limit also caps Gemini spend, since a Quick Play game can make the bot's first model call without further input.
- **Browser hardening.** Every response carries a Content-Security-Policy (scripts only from this origin; an inline import map would be allowed by its hash), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, a referrer policy, a permissions policy, and HSTS over HTTPS. Deals are shuffled with `SecureRandom`.
- **Health.** `/actuator/health` reports `DOWN` when the game store is unreachable; it's the platform health-check path.

## Logging

On Cloud Run the deploy script sets `LOGGING_STRUCTURED_FORMAT_CONSOLE` so every log event is one JSON object with a Cloud Logging `severity`, `message` and `time` (stack traces stay in their entry and reach Error Reporting). Messages are `key=value` style, so they work well in Logs Explorer queries and log-based metrics, for example `jsonPayload.message:"autoplay_decision"` (every engine decision, with `source=llm|plan_cache|forced|heuristic` and the fallback `reason` — the LLM vs heuristic ratio), `jsonPayload.message:"autoplay_applied"` (each bot move actually played, with `source=forced|engine|fallback|last_resort`) or `jsonPayload.message:"auth_rejected"`. Local runs keep Spring's readable console format.

## Realtime updates and fallback reads

Game changes arrive over `/ws/games/{code}` and public-table invalidations arrive over `/ws/lobbies`. Lobby events carry only a monotonic revision; clients then read the authoritative `/api/lobbies` projection. A healthy connection reduces game health reads to once every 30 seconds and lobby health reads to once every 60 seconds, instead of fixed three-/four-second polling.

Both channels reconnect with bounded exponential backoff and jitter. If a socket is unavailable, HTTP fallback refreshes continue (with backoff after lobby read failures). Hidden tabs close sockets and pause refresh/heartbeat work, then reconnect and reconcile immediately when visible again.

## Game state storage

By default (local development), game state is stored in-memory.

When running on Cloud Run, the app automatically switches to Firestore-backed storage (detected via the `K_SERVICE` environment variable), so rooms survive instance restarts.

If your Firestore database id is not `(default)`, set:

- `FIRESTORE_DATABASE_ID` (for example `durak-store`)

Rooms use activity-based expiration: waiting lobbies expire after 30 minutes of inactivity (and always within 2 hours of entering the current lobby phase), active games after 24 hours, and finished games after 60 minutes. Returning to the lobby after a played round starts a fresh lobby phase. These values can be changed with `LOBBY_IDLE_MINUTES`, `LOBBY_MAX_AGE_MINUTES`, `ACTIVE_GAME_IDLE_HOURS`, and `FINISHED_GAME_RETENTION_MINUTES`.

The API and lobby list enforce expiration immediately. Firestore documents also carry `lastActivityAt`, `lobbyStartedAt`, and `expireAt`; configure a TTL policy on `games.expireAt` for eventual storage cleanup. The deploy script enables that policy by default because Firestore TTL deletion is asynchronous and is not used for live lobby correctness.

## Auto-play (Gemini)

The host can add bot players in the lobby. Bots use the primary LLM to choose moves. Every move the engine returns is checked against the bot's legal moves: card codes are normalized (`6c` is `6C`), and an answer that is still illegal (or missing, or unparseable) is replaced by the deterministic heuristic's move. The heuristic is also used whenever the model is disabled or unavailable. A decision with a single forced option never calls the model, and neither does a bot that already passed in the current bout: nothing has changed since (any new card clears passes), so it waits for the other players instead of being pushed into a throw-in it just declined. When the model defends against several attacks at once it returns a plan for all of them; the bot replays the rest of that plan on its next defend decisions in the same bout (while the table still matches it exactly) instead of asking again.

Environment variables:

- `GEMINI_API_KEY` (empty by default; when absent, bots use heuristic fallback; Cloud Run receives this from Secret Manager)
- `AUTOPLAY_GEMINI_ENABLED` (`true` by default)
- `AUTOPLAY_GEMINI_MODEL` (`gemini-3.8-flash` by default; a `models/` prefix is accepted)
- `AUTOPLAY_GEMINI_BASE_URL` (`https://generativelanguage.googleapis.com/v1beta` by default)
- `AUTOPLAY_GEMINI_THINKING_LEVEL` (`HIGH` by default; decisions with three or more legal options)
- `AUTOPLAY_GEMINI_SIMPLE_THINKING_LEVEL` (`LOW` by default; decisions with at most two legal options, such as one throw-in card vs pass or one beating card vs take; empty means the same level as above)
- `AUTOPLAY_GEMINI_REASONING_BUDGET_SECONDS` (`30` by default; prompt-level budgeted reasoning instruction for Gemma models)
- `AUTOPLAY_REQUEST_TIMEOUT_MS` (`30000` by default)
- `AUTOPLAY_GEMINI_CIRCUIT_BREAKER_FAILURE_THRESHOLD` (`3` by default; after this many consecutive timeouts, I/O errors or HTTP 429/5xx responses, bots stop calling the model; `0` disables the breaker)
- `AUTOPLAY_GEMINI_CIRCUIT_BREAKER_COOLDOWN_MS` (`60000` by default; how long the breaker stays open before a single probe call decides whether to resume)
- `AUTOPLAY_GEMINI_MAX_CALLS_PER_MINUTE` (`120` by default; global token bucket across all games with a burst of ten seconds' worth of calls, so spend stays capped even if request-level rate limiting is bypassed; `0` disables the cap)

Model capability overrides (each accepts `auto`, `true`, or `false`; `auto` derives the value from the model family and version parsed from the id, so future Gemini versions are handled without code changes):

- `AUTOPLAY_GEMINI_JSON_MODE` (`auto`: enabled except for Gemma 3 models)
- `AUTOPLAY_GEMINI_SYSTEM_INSTRUCTION` (`auto`: enabled except for Gemma 3 models)
- `AUTOPLAY_GEMINI_THINKING_CONFIG` (`auto`: enabled for Gemini 3 and newer)
- `AUTOPLAY_GEMINI_PROMPT_REASONING_BUDGET` (`auto`: enabled for Gemma models)

Gemini 3 and newer keep their default sampling settings; `temperature: 0` is only pinned for Gemini 1.x/2.x and Gemma models.

The prompt only contains what a human in the bot's seat can see: its own hand, the table, the trump, other seats' hand sizes as the table shows them (exact below six, otherwise `6+`, the same for the take limit), whether the talon is empty or down to the face-up trump (never the exact count), the number of completed bouts, discarded cards and publicly picked-up cards. Seats are labelled relative to the bot (`you`, `P2`, `P3`, `P4` in turn order, with role and partner/opponent flags); player names and ids are never sent. The rules and instructions form a byte-identical prefix and all per-turn data comes last, so Gemini's implicit prompt caching can reuse the prefix (visible as `cachedTokens` in the logs).

Each model call logs one line, and each bot decision logs one line:

```text
autoplay_llm_call code=… player=… model=… thinkingLevel=… latencyMs=… httpStatus=… promptTokens=… cachedTokens=… outputTokens=… thoughtTokens=… totalTokens=… finishReason=… error=…
autoplay_decision code=… player=… source=llm|plan_cache|forced|heuristic reason=… action=… card=… attackCard=… options=…
```

`reason` says why the heuristic was used (`disabled`, `circuit_open`, `budget_exhausted`, `primary_model_failed`, `unparseable`, `illegal_model_action`, `no_legal_moves`) or why no call was needed (`single_legal_option`, `already_passed`), and is `none` for model and plan-cache decisions. An illegal model answer is echoed as `model=TYPE/card/attackCard`. The API key and player names are never logged.

The heuristic bot is deterministic and also sees only its own seat's information. It compares the cheapest complete defence with transferring and with taking the table, weighted by game phase (it takes a low card early rather than burn a high trump, but not once the talon is empty); it keeps trumps and aces while the talon still has cards, only dumps low non-trumps on a defender who is taking, and in the endgame plays to run out of cards, including leading cards that public card counting shows nobody can beat.

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
- `GEMINI_SECRET` (default `gemini-api-key`)
- `GEMINI_SECRET_PROJECT` (defaults to `PROJECT_ID`; set it when the secret lives elsewhere)
- `GEMINI_SECRET_VERSION` (default `latest`; set a numeric version to pin deployments)
- `AUTOPLAY_GEMINI_MODEL` (default `gemini-3.8-flash`)
- `RUNTIME_SERVICE_ACCOUNT` (auto-detected from an existing service, otherwise the project's default compute service account)
- `CONCURRENCY` (default `200`; every open tab holds a websocket that counts against it)
- `REQUEST_TIMEOUT` (default `3600` seconds; Cloud Run closes websockets at this limit)
- `MIN_INSTANCES` (default `0`, i.e. scale to zero when idle)
- `LOG_FORMATTER` (default `com.example.durakgame.logging.CloudLoggingJsonFormatter`)

Example:

```bash
PROJECT_ID="my-gcp-project" REGION="europe-west1" SERVICE="durak-prod" TAG="$(git rev-parse --short HEAD)" ./scripts/deploy-cloud-run.sh
```

The deployed Durak service and its Gemini key can live in different projects. The script resolves the secret project's numeric id, verifies the configured secret and version without reading the secret value, grants the Cloud Run runtime identity `roles/secretmanager.secretAccessor` on that one secret, and injects it as `GEMINI_API_KEY`. The key is never passed to Cloud Build or stored in the container image. The account running the script must be able to update that secret's IAM policy and act as the selected runtime service account.

For the current production layout, Durak runs in `andreyg-main` while `gemini-api-key` is stored in project `527294552477`:

```bash
PROJECT_ID="andreyg-main" GEMINI_SECRET_PROJECT="527294552477" REGION="us-west1" ./scripts/deploy-cloud-run.sh
```

### Single-instance deployment requirement

The deploy script pins the service to one instance (`--max-instances 1`). Keep it that way for now:

- Websocket sessions, lobby invalidation revisions, and the bot "thinking..." status live in instance memory; a second instance would split rooms across instances.
- Concurrent-write protection uses in-process per-game locks (plus a stale-version check on every Firestore save as a safety net). Multiple instances would rely on the version check alone and reject racing writes instead of serializing them.

Because a websocket occupies a request slot for as long as the tab is open, the single instance's `--concurrency` (200 by default, Cloud Run's maximum is 1000) is effectively the number of simultaneously open tabs it can serve. The script also sets `--timeout 3600` so Cloud Run doesn't cut every websocket after its default five minutes, and `--cpu-boost` for faster cold starts. The container defaults `JAVA_OPTS` to `-XX:MaxRAMPercentage=75.0`; without it the JVM would cap the heap at a quarter of the 512 MiB instance.

Game state itself persists in Firestore on Cloud Run, so a restart does not lose active rooms. To scale beyond one instance later, move websocket fan-out and bot status to a shared channel (for example Firestore listeners or Pub/Sub).
