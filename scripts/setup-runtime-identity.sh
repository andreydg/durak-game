#!/usr/bin/env bash
set -euo pipefail

# One-time setup of a dedicated runtime identity for the Durak Cloud Run service.
#
# Without it the service runs as the project's default compute service account, which holds broad
# project roles (Editor, Cloud Run Admin, ...) and is usually shared with other services and with
# Cloud Build. This creates durak-runtime@PROJECT_ID.iam.gserviceaccount.com with only what the
# game needs:
#   - roles/datastore.user, limited by an IAM condition to the game's Firestore database
#   - roles/secretmanager.secretAccessor on the Gemini API key secret
# Logs go to stdout, which Cloud Run collects without any role.
#
# Safe to re-run. It does not touch the running service: README "Runtime identity" shows how to
# check the new identity on a revision without traffic and then switch to it. Deploys after that
# keep it (the deploy script reads the service's current identity).
#
# Usage:
#   PROJECT_ID=my-gcp-project FIRESTORE_DATABASE_ID=durak-store ./scripts/setup-runtime-identity.sh
#
# Optional env:
#   RUNTIME_SA_NAME=durak-runtime
#   GEMINI_SECRET=gemini-api-key
#   GEMINI_SECRET_PROJECT=my-secret-project (defaults to PROJECT_ID)

: "${PROJECT_ID:?Set PROJECT_ID (your GCP project id)}"
FIRESTORE_DATABASE_ID="${FIRESTORE_DATABASE_ID:-(default)}"
RUNTIME_SA_NAME="${RUNTIME_SA_NAME:-durak-runtime}"
GEMINI_SECRET="${GEMINI_SECRET:-gemini-api-key}"
GEMINI_SECRET_PROJECT="${GEMINI_SECRET_PROJECT:-${PROJECT_ID}}"
GCLOUD_BIN="${GCLOUD_BIN:-gcloud}"
# A new service account can take a few seconds to become visible to IAM policy updates.
RETRY_DELAY_SECONDS="${RETRY_DELAY_SECONDS:-5}"

RUNTIME_SA="${RUNTIME_SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"
DATABASE_RESOURCE="projects/${PROJECT_ID}/databases/${FIRESTORE_DATABASE_ID}"

retry() {
  local attempt
  for attempt in 1 2 3 4 5 6; do
    if "$@"; then
      return 0
    fi
    if [[ "${attempt}" -lt 6 ]]; then
      echo "    retrying in ${RETRY_DELAY_SECONDS}s..." >&2
      sleep "${RETRY_DELAY_SECONDS}"
    fi
  done
  return 1
}

echo "==> Project: ${PROJECT_ID}"
echo "==> Runtime service account: ${RUNTIME_SA}"
echo "==> Firestore database: ${DATABASE_RESOURCE}"
echo "==> Gemini secret: ${GEMINI_SECRET} in ${GEMINI_SECRET_PROJECT}"

if "${GCLOUD_BIN}" iam service-accounts describe "${RUNTIME_SA}" --project "${PROJECT_ID}" >/dev/null 2>&1; then
  echo "==> Service account already exists"
else
  echo "==> Creating service account"
  "${GCLOUD_BIN}" iam service-accounts create "${RUNTIME_SA_NAME}" \
    --display-name "Durak game runtime" \
    --description "Runtime identity of the durak-game Cloud Run service" \
    --project "${PROJECT_ID}"
fi

echo "==> Granting Firestore access to ${FIRESTORE_DATABASE_ID} only"
retry "${GCLOUD_BIN}" projects add-iam-policy-binding "${PROJECT_ID}" \
  --member "serviceAccount:${RUNTIME_SA}" \
  --role roles/datastore.user \
  --condition "expression=resource.name==\"${DATABASE_RESOURCE}\",title=durak-game database only" \
  --quiet >/dev/null

echo "==> Granting access to the Gemini API key secret"
retry "${GCLOUD_BIN}" secrets add-iam-policy-binding "${GEMINI_SECRET}" \
  --project "${GEMINI_SECRET_PROJECT}" \
  --member "serviceAccount:${RUNTIME_SA}" \
  --role roles/secretmanager.secretAccessor \
  --quiet >/dev/null

echo
echo "Done. The service still runs as its previous identity; see README \"Runtime identity\" to switch."
