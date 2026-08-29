#!/usr/bin/env bash
set -euo pipefail

# Deploy Durak to Cloud Run using the repository Dockerfile.
#
# Usage:
#   PROJECT_ID=my-gcp-project REGION=us-central1 ./scripts/deploy-cloud-run.sh
#
# Optional env:
#   SERVICE=durak-game
#   REPOSITORY=durak-game
#   TAG=latest
#   ALLOW_UNAUTHENTICATED=true
#   FIRESTORE_DATABASE_ID=(default)
#   CONFIGURE_FIRESTORE_TTL=true
#   GEMINI_SECRET=gemini-api-key
#   GEMINI_SECRET_PROJECT=527294552477
#   GEMINI_SECRET_VERSION=latest
#   AUTOPLAY_GEMINI_MODEL=gemini-3.7-flash
#   RUNTIME_SERVICE_ACCOUNT=service-account@project.iam.gserviceaccount.com

: "${PROJECT_ID:?Set PROJECT_ID (your GCP project id)}"
REGION="${REGION:-us-central1}"
SERVICE="${SERVICE:-durak-game}"
REPOSITORY="${REPOSITORY:-durak-game}"
TAG="${TAG:-latest}"
ALLOW_UNAUTHENTICATED="${ALLOW_UNAUTHENTICATED:-true}"
FIRESTORE_DATABASE_ID="${FIRESTORE_DATABASE_ID:-(default)}"
CONFIGURE_FIRESTORE_TTL="${CONFIGURE_FIRESTORE_TTL:-true}"
GEMINI_SECRET="${GEMINI_SECRET:-gemini-api-key}"
GEMINI_SECRET_PROJECT="${GEMINI_SECRET_PROJECT:-527294552477}"
GEMINI_SECRET_VERSION="${GEMINI_SECRET_VERSION:-latest}"
AUTOPLAY_GEMINI_MODEL="${AUTOPLAY_GEMINI_MODEL:-gemini-3.7-flash}"
RUNTIME_SERVICE_ACCOUNT="${RUNTIME_SERVICE_ACCOUNT:-}"
GCLOUD_BIN="${GCLOUD_BIN:-gcloud}"

IMAGE="${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPOSITORY}/${SERVICE}:${TAG}"

GEMINI_SECRET_PROJECT_NUMBER="$("${GCLOUD_BIN}" projects describe "${GEMINI_SECRET_PROJECT}" \
  --format='value(projectNumber)')"
if [[ ! "${GEMINI_SECRET_PROJECT_NUMBER}" =~ ^[0-9]+$ ]]; then
  echo "Error: could not resolve project number for GEMINI_SECRET_PROJECT=${GEMINI_SECRET_PROJECT}" >&2
  exit 1
fi
GEMINI_SECRET_RESOURCE="projects/${GEMINI_SECRET_PROJECT_NUMBER}/secrets/${GEMINI_SECRET}"

if [[ -z "${RUNTIME_SERVICE_ACCOUNT}" ]]; then
  RUNTIME_SERVICE_ACCOUNT="$("${GCLOUD_BIN}" run services describe "${SERVICE}" \
    --region "${REGION}" \
    --project "${PROJECT_ID}" \
    --format='value(spec.template.spec.serviceAccountName)' \
    2>/dev/null || true)"
fi
if [[ -z "${RUNTIME_SERVICE_ACCOUNT}" ]]; then
  PROJECT_NUMBER="$("${GCLOUD_BIN}" projects describe "${PROJECT_ID}" \
    --format='value(projectNumber)')"
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
echo "==> Gemini secret: ${GEMINI_SECRET_RESOURCE}:${GEMINI_SECRET_VERSION}"
echo "==> Runtime service account: ${RUNTIME_SERVICE_ACCOUNT}"

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

echo "==> Building container image with Cloud Build"
"${GCLOUD_BIN}" builds submit --tag "${IMAGE}" --project "${PROJECT_ID}"

echo "==> Deploying to Cloud Run"
# max-instances must stay 1: websocket fan-out, bot-thinking status, and the
# per-game locks that prevent concurrent-write races all live in instance memory.
DEPLOY_ARGS=(
  --image "${IMAGE}"
  --platform managed
  --region "${REGION}"
  --port 8080
  --max-instances 1
  --memory 512Mi
  --cpu 1
  --service-account "${RUNTIME_SERVICE_ACCOUNT}"
  --update-env-vars "AUTOPLAY_GEMINI_MODEL=${AUTOPLAY_GEMINI_MODEL}"
  --update-secrets "GEMINI_API_KEY=${GEMINI_SECRET_RESOURCE}:${GEMINI_SECRET_VERSION}"
  --project "${PROJECT_ID}"
)

if [[ "${ALLOW_UNAUTHENTICATED}" == "true" ]]; then
  DEPLOY_ARGS+=(--allow-unauthenticated)
else
  DEPLOY_ARGS+=(--no-allow-unauthenticated)
fi

"${GCLOUD_BIN}" run deploy "${SERVICE}" "${DEPLOY_ARGS[@]}"

echo
echo "Done."
echo "Gemini API access is injected from Secret Manager; no key is stored in the image."
echo "Note: the service is pinned to a single instance (--max-instances 1)."
echo "Websocket sessions and per-game locks are in-memory, so scaling out would"
echo "split rooms and reintroduce concurrent-write races. Game state persists in Firestore."
