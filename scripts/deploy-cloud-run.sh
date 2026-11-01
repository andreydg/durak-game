#!/usr/bin/env bash
set -euo pipefail

# Deploy Durak to Cloud Run using the repository Dockerfile.
#
# Usage:
#   PROJECT_ID=my-gcp-project REGION=us-central1 ./scripts/deploy-cloud-run.sh
#
# Releases from main go through .github/workflows/deploy.yml, which builds the image on the runner
# and calls this script with DEPLOY_ONLY=true and IMAGE=<pushed image>. Run it by hand for the first
# deploy, after changing the secret or TTL settings, or when the workflow can't run.
#
# Optional env:
#   SERVICE=durak-game
#   REPOSITORY=durak-game
#   TAG=latest
#   IMAGE=<registry>/<image>:<tag> (deploy this prebuilt image instead of building one with Cloud Build)
#   DEPLOY_ONLY=false        (true: only roll out a new revision. Skips enabling APIs, the secret check
#                             and its IAM binding, the TTL policy, repository creation and the public
#                             access policy, and keeps the service's secret mapping, so the caller only
#                             needs to deploy this one service.)
#   ALLOW_UNAUTHENTICATED=true
#   FIRESTORE_DATABASE_ID=(default)
#   CONFIGURE_FIRESTORE_TTL=true
#   GEMINI_SECRET=gemini-api-key
#   GEMINI_SECRET_PROJECT=my-secret-project (defaults to PROJECT_ID)
#   GEMINI_SECRET_VERSION=latest
#   AUTOPLAY_GEMINI_MODEL=gemini-3.8-flash
#   AUTOPLAY_PROVIDER=gemini (or anthropic: Claude plays the bots)
#   AUTOPLAY_ANTHROPIC_ORGANIZATION_ID, AUTOPLAY_ANTHROPIC_WORKSPACE_ID,
#   AUTOPLAY_ANTHROPIC_SERVICE_ACCOUNT_ID, AUTOPLAY_ANTHROPIC_FEDERATION_RULE_ID
#                            (Claude's workload identity federation, from the Claude Console: identifiers,
#                             not credentials)
#   AUTOPLAY_ANTHROPIC_INFERENCE_GEO=global (or us)
#                            (Each AUTOPLAY_PROVIDER/AUTOPLAY_ANTHROPIC_* value is set on the service only
#                             when given; one left unset keeps the service's current value.)
#   RUNTIME_SERVICE_ACCOUNT=service-account@project.iam.gserviceaccount.com
#   CONCURRENCY=200          (max concurrent requests on the single instance; each open tab holds a websocket)
#   REQUEST_TIMEOUT=3600     (seconds; Cloud Run closes websockets at this limit, default would be 300)
#   MIN_INSTANCES=0          (0 = scale to zero when idle)
#   LOG_FORMATTER=com.example.durakgame.logging.CloudLoggingJsonFormatter (structured JSON logs)

: "${PROJECT_ID:?Set PROJECT_ID (your GCP project id)}"
REGION="${REGION:-us-central1}"
SERVICE="${SERVICE:-durak-game}"
REPOSITORY="${REPOSITORY:-durak-game}"
TAG="${TAG:-latest}"
ALLOW_UNAUTHENTICATED="${ALLOW_UNAUTHENTICATED:-true}"
FIRESTORE_DATABASE_ID="${FIRESTORE_DATABASE_ID:-(default)}"
CONFIGURE_FIRESTORE_TTL="${CONFIGURE_FIRESTORE_TTL:-true}"
GEMINI_SECRET="${GEMINI_SECRET:-gemini-api-key}"
GEMINI_SECRET_PROJECT="${GEMINI_SECRET_PROJECT:-${PROJECT_ID}}"
GEMINI_SECRET_VERSION="${GEMINI_SECRET_VERSION:-latest}"
AUTOPLAY_GEMINI_MODEL="${AUTOPLAY_GEMINI_MODEL:-gemini-3.8-flash}"
RUNTIME_SERVICE_ACCOUNT="${RUNTIME_SERVICE_ACCOUNT:-}"
CONCURRENCY="${CONCURRENCY:-200}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-3600}"
MIN_INSTANCES="${MIN_INSTANCES:-0}"
# One JSON object per log line with a Cloud Logging severity (see CloudLoggingJsonFormatter).
LOG_FORMATTER="${LOG_FORMATTER:-com.example.durakgame.logging.CloudLoggingJsonFormatter}"
GCLOUD_BIN="${GCLOUD_BIN:-gcloud}"
DEPLOY_ONLY="${DEPLOY_ONLY:-false}"

# Settings the service keeps from one revision to the next unless they are given here.
ENV_VARS="AUTOPLAY_GEMINI_MODEL=${AUTOPLAY_GEMINI_MODEL},LOGGING_STRUCTURED_FORMAT_CONSOLE=${LOG_FORMATTER}"
for name in AUTOPLAY_PROVIDER AUTOPLAY_ANTHROPIC_ORGANIZATION_ID AUTOPLAY_ANTHROPIC_WORKSPACE_ID \
    AUTOPLAY_ANTHROPIC_SERVICE_ACCOUNT_ID AUTOPLAY_ANTHROPIC_FEDERATION_RULE_ID AUTOPLAY_ANTHROPIC_INFERENCE_GEO; do
  value="${!name:-}"
  if [[ -z "${value}" ]]; then
    continue
  fi
  # Ids, a provider and a geo: nothing that could split the --update-env-vars list.
  if [[ ! "${value}" =~ ^[A-Za-z0-9_.-]+$ ]]; then
    echo "Error: ${name} may only contain letters, digits, '.', '_' and '-'." >&2
    exit 1
  fi
  ENV_VARS+=",${name}=${value}"
done

PREBUILT_IMAGE="${IMAGE:-}"
IMAGE="${PREBUILT_IMAGE:-${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPOSITORY}/${SERVICE}:${TAG}}"

GEMINI_SECRET_RESOURCE=""
if [[ "${DEPLOY_ONLY}" != "true" ]]; then
  GEMINI_SECRET_PROJECT_NUMBER=""
  if ! GEMINI_SECRET_PROJECT_NUMBER="$("${GCLOUD_BIN}" projects describe "${GEMINI_SECRET_PROJECT}" \
    --format='value(projectNumber)')"; then
    echo "Error: could not resolve project number for GEMINI_SECRET_PROJECT=${GEMINI_SECRET_PROJECT}" >&2
    exit 1
  fi
  if [[ ! "${GEMINI_SECRET_PROJECT_NUMBER}" =~ ^[0-9]+$ ]]; then
    echo "Error: could not resolve project number for GEMINI_SECRET_PROJECT=${GEMINI_SECRET_PROJECT}" >&2
    exit 1
  fi
  GEMINI_SECRET_RESOURCE="projects/${GEMINI_SECRET_PROJECT_NUMBER}/secrets/${GEMINI_SECRET}"
fi

if [[ -z "${RUNTIME_SERVICE_ACCOUNT}" ]]; then
  RUNTIME_SERVICE_ACCOUNT="$("${GCLOUD_BIN}" run services describe "${SERVICE}" \
    --region "${REGION}" \
    --project "${PROJECT_ID}" \
    --format='value(spec.template.spec.serviceAccountName)' \
    2>/dev/null || true)"
fi
if [[ -z "${RUNTIME_SERVICE_ACCOUNT}" && "${DEPLOY_ONLY}" == "true" ]]; then
  echo "Error: DEPLOY_ONLY updates an existing service, but ${SERVICE} could not be read; set RUNTIME_SERVICE_ACCOUNT or run a full deploy." >&2
  exit 1
fi
if [[ -z "${RUNTIME_SERVICE_ACCOUNT}" ]]; then
  PROJECT_NUMBER=""
  if ! PROJECT_NUMBER="$("${GCLOUD_BIN}" projects describe "${PROJECT_ID}" \
    --format='value(projectNumber)')"; then
    echo "Error: could not resolve project number for PROJECT_ID=${PROJECT_ID}" >&2
    exit 1
  fi
  if [[ ! "${PROJECT_NUMBER}" =~ ^[0-9]+$ ]]; then
    echo "Error: could not resolve project number for PROJECT_ID=${PROJECT_ID}" >&2
    exit 1
  fi
  RUNTIME_SERVICE_ACCOUNT="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"
fi

echo "==> Using project: ${PROJECT_ID}"
echo "==> Region: ${REGION}"
echo "==> Service: ${SERVICE}"
echo "==> Image: ${IMAGE}"
echo "==> Gemini model: ${AUTOPLAY_GEMINI_MODEL}"
echo "==> Bot provider: ${AUTOPLAY_PROVIDER:-kept from the current service (gemini when never set)}"
if [[ "${DEPLOY_ONLY}" == "true" ]]; then
  echo "==> Gemini secret: kept from the current service"
else
  echo "==> Gemini secret: ${GEMINI_SECRET_RESOURCE}:${GEMINI_SECRET_VERSION}"
fi
echo "==> Runtime service account: ${RUNTIME_SERVICE_ACCOUNT}"
echo "==> Concurrency: ${CONCURRENCY}, request timeout: ${REQUEST_TIMEOUT}s, min instances: ${MIN_INSTANCES}"

# One-time project setup; repeated runs are harmless. DEPLOY_ONLY skips it so the caller needs no
# rights beyond deploying the service.
setup_project() {
  echo "==> Enabling required APIs"
  "${GCLOUD_BIN}" services enable \
    run.googleapis.com \
    cloudbuild.googleapis.com \
    artifactregistry.googleapis.com \
    firestore.googleapis.com \
    --project "${PROJECT_ID}"
  "${GCLOUD_BIN}" services enable secretmanager.googleapis.com \
    --project "${GEMINI_SECRET_PROJECT}"

  echo "==> Verifying Gemini secret metadata"
  if ! "${GCLOUD_BIN}" secrets describe "${GEMINI_SECRET}" \
    --project "${GEMINI_SECRET_PROJECT}" >/dev/null 2>&1; then
    echo "Error: could not verify ${GEMINI_SECRET_RESOURCE}; check that it exists and the deployer can describe it." >&2
    exit 1
  fi
  if ! "${GCLOUD_BIN}" secrets versions describe "${GEMINI_SECRET_VERSION}" \
    --secret "${GEMINI_SECRET}" \
    --project "${GEMINI_SECRET_PROJECT}" >/dev/null 2>&1; then
    echo "Error: could not verify ${GEMINI_SECRET_RESOURCE}:${GEMINI_SECRET_VERSION}." >&2
    exit 1
  fi

  echo "==> Granting the runtime identity access to the Gemini secret"
  "${GCLOUD_BIN}" secrets add-iam-policy-binding "${GEMINI_SECRET}" \
    --project "${GEMINI_SECRET_PROJECT}" \
    --member "serviceAccount:${RUNTIME_SERVICE_ACCOUNT}" \
    --role roles/secretmanager.secretAccessor \
    --quiet >/dev/null

  if [[ "${CONFIGURE_FIRESTORE_TTL}" == "true" ]]; then
    echo "==> Ensuring Firestore TTL policy on games.expireAt"
    if ! "${GCLOUD_BIN}" firestore fields ttls update expireAt \
      --collection-group=games \
      --database="${FIRESTORE_DATABASE_ID}" \
      --enable-ttl \
      --async \
      --project "${PROJECT_ID}"; then
      echo "Warning: Firestore TTL policy could not be configured; continuing deployment." >&2
    fi
  fi

  echo "==> Ensuring Artifact Registry repository exists"
  if ! "${GCLOUD_BIN}" artifacts repositories describe "${REPOSITORY}" \
    --location "${REGION}" \
    --project "${PROJECT_ID}" >/dev/null 2>&1; then
    "${GCLOUD_BIN}" artifacts repositories create "${REPOSITORY}" \
      --repository-format docker \
      --location "${REGION}" \
      --description "Docker images for Durak Cloud Run deploys" \
      --project "${PROJECT_ID}"
  fi
}

if [[ "${DEPLOY_ONLY}" == "true" ]]; then
  echo "==> Deploy only: skipping API, secret, TTL and repository setup"
else
  setup_project
fi

if [[ -n "${PREBUILT_IMAGE}" ]]; then
  echo "==> Using prebuilt image"
else
  echo "==> Building container image with Cloud Build"
  "${GCLOUD_BIN}" builds submit --tag "${IMAGE}" --project "${PROJECT_ID}"
fi

echo "==> Deploying to Cloud Run"
# max-instances must stay 1: websocket fan-out, bot-thinking status, and the
# per-game locks that prevent concurrent-write races all live in instance memory.
# --max caps the service as a whole and --max-instances each revision; Cloud Run uses the lower,
# so setting both keeps a console edit of either one from scaling the game out.
# Every open tab holds a websocket, which counts against --concurrency for its whole
# lifetime, so the Cloud Run default of 80 would cap the whole site at ~80 open tabs.
DEPLOY_ARGS=(
  --image "${IMAGE}"
  --platform managed
  --region "${REGION}"
  --port 8080
  --max 1
  --max-instances 1
  --min-instances "${MIN_INSTANCES}"
  --concurrency "${CONCURRENCY}"
  --timeout "${REQUEST_TIMEOUT}"
  --cpu-boost
  --memory 512Mi
  --cpu 1
  --service-account "${RUNTIME_SERVICE_ACCOUNT}"
  --update-env-vars "${ENV_VARS}"
  --project "${PROJECT_ID}"
)

if [[ "${DEPLOY_ONLY}" != "true" ]]; then
  DEPLOY_ARGS+=(--update-secrets "GEMINI_API_KEY=${GEMINI_SECRET_RESOURCE}:${GEMINI_SECRET_VERSION}")
  if [[ "${ALLOW_UNAUTHENTICATED}" == "true" ]]; then
    DEPLOY_ARGS+=(--allow-unauthenticated)
  else
    DEPLOY_ARGS+=(--no-allow-unauthenticated)
  fi
fi

"${GCLOUD_BIN}" run deploy "${SERVICE}" "${DEPLOY_ARGS[@]}"

echo
echo "Done."
echo "Gemini API access is injected from Secret Manager; no key is stored in the image."
echo "Note: the service is pinned to a single instance (--max 1 and --max-instances 1)."
echo "Websocket sessions and per-game locks are in-memory, so scaling out would"
echo "split rooms and reintroduce concurrent-write races. Game state persists in Firestore."
