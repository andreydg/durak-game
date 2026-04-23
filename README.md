# Durak Game

Spring Boot multiplayer Durak game with a browser UI and websocket updates.

## Game state storage

By default (local development), game state is stored in-memory.

When running on Cloud Run, the app automatically switches to Firestore-backed storage (detected via the `K_SERVICE` environment variable), so rooms survive instance restarts.

If your Firestore database id is not `(default)`, set:

- `FIRESTORE_DATABASE_ID` (for example `durak-store`)

Games written to Firestore include an `expireAt` timestamp set to 24 hours after game creation. Configure Firestore TTL policy on `expireAt` to let Firestore delete stale game documents automatically.

## Auto-play (Qwen3.5-Omni-flash)

The host can add bot players in the lobby. Bots use an LLM to choose moves, but every move is validated server-side; when the model is unavailable or returns invalid output, the game falls back to a deterministic heuristic move.

Environment variables:

- `AUTOPLAY_QWEN_ENABLED` (`false` by default)
- `AUTOPLAY_QWEN_API_KEY` (empty by default)
- `AUTOPLAY_QWEN_MODEL` (`qwen3.5-omni-flash` by default)
- `AUTOPLAY_QWEN_BASE_URL` (`https://dashscope-intl.aliyuncs.com/compatible-mode/v1` by default)
- `AUTOPLAY_REQUEST_TIMEOUT_MS` (`3500` by default)

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

### Notes about current game state storage

The app currently stores game rooms in memory. Because of that:

- Deploy with a single instance (`--max-instances 1`) to avoid split rooms.
- If the instance restarts (or scales to zero and wakes up), active rooms are lost.

For production-grade persistence, move room/game state to an external store (for example Redis or SQL).
