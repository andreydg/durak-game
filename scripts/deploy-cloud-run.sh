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

: "${PROJECT_ID:?Set PROJECT_ID (your GCP project id)}"
REGION="${REGION:-us-central1}"
SERVICE="${SERVICE:-durak-game}"
REPOSITORY="${REPOSITORY:-durak-game}"
TAG="${TAG:-latest}"
ALLOW_UNAUTHENTICATED="${ALLOW_UNAUTHENTICATED:-true}"

IMAGE="${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPOSITORY}/${SERVICE}:${TAG}"

echo "==> Using project: ${PROJECT_ID}"
echo "==> Region: ${REGION}"
echo "==> Service: ${SERVICE}"
echo "==> Image: ${IMAGE}"

echo "==> Enabling required APIs"
gcloud services enable \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  --project "${PROJECT_ID}"

echo "==> Ensuring Artifact Registry repository exists"
if ! gcloud artifacts repositories describe "${REPOSITORY}" \
  --location "${REGION}" \
  --project "${PROJECT_ID}" >/dev/null 2>&1; then
  gcloud artifacts repositories create "${REPOSITORY}" \
    --repository-format docker \
    --location "${REGION}" \
    --description "Docker images for Durak Cloud Run deploys" \
    --project "${PROJECT_ID}"
fi

echo "==> Building container image with Cloud Build"
gcloud builds submit --tag "${IMAGE}" --project "${PROJECT_ID}"

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
  --project "${PROJECT_ID}"
)

if [[ "${ALLOW_UNAUTHENTICATED}" == "true" ]]; then
  DEPLOY_ARGS+=(--allow-unauthenticated)
else
  DEPLOY_ARGS+=(--no-allow-unauthenticated)
fi

gcloud run deploy "${SERVICE}" "${DEPLOY_ARGS[@]}"

echo
echo "Done."
echo "Note: the service is pinned to a single instance (--max-instances 1)."
echo "Websocket sessions and per-game locks are in-memory, so scaling out would"
echo "split rooms and reintroduce concurrent-write races. Game state persists in Firestore."
