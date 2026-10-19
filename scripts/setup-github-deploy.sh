#!/usr/bin/env bash
set -euo pipefail

# One-time setup for .github/workflows/deploy.yml: lets that workflow, and only that workflow on
# main of this repository, deploy the Cloud Run service through Workload Identity Federation.
# No service account keys are created. Safe to re-run; re-run it after changing the service's
# runtime identity so the deployer may act as the new one.
#
# Creates or updates:
#   - service account durak-deployer@PROJECT_ID with
#       roles/run.developer          on the Cloud Run service only
#       roles/run.viewer             on the project (read-only; gcloud reads revisions while deploying)
#       roles/artifactregistry.writer on the image repository only
#       roles/iam.serviceAccountUser  on the service's runtime identity only
#   - workload identity pool "github" with an OIDC provider that accepts GitHub tokens only for this
#     repository (by numeric id, so a renamed or recreated repository doesn't match), ref
#     refs/heads/main, and the deploy workflow file
#   - roles/iam.workloadIdentityUser on the deployer for that repository's tokens
#   - the GitHub repository variables the workflow reads
#
# Usage:
#   PROJECT_ID=andreyg-main REGION=us-west1 GITHUB_REPOSITORY=andreydg/durak-game \
#     PUBLIC_URL=https://durak.andreyg.com ./scripts/setup-github-deploy.sh
#
# Optional env:
#   SERVICE=durak-game  REPOSITORY=durak-game  DEPLOYER_SA_NAME=durak-deployer
#   POOL_ID=github  PROVIDER_ID=durak-game
#   RUNTIME_SERVICE_ACCOUNT=... (defaults to the service's current identity)
#   ALLOW_DEFAULT_COMPUTE_SA=false (see below)
#
# The deployer may act as the service's runtime identity, so whatever that identity can do, code
# merged to main can do. The script therefore refuses while the service still runs as the project's
# default compute service account (broad project roles): give the service its own identity first
# (scripts/setup-runtime-identity.sh), or set ALLOW_DEFAULT_COMPUTE_SA=true to accept the risk.

: "${PROJECT_ID:?Set PROJECT_ID (your GCP project id)}"
: "${GITHUB_REPOSITORY:?Set GITHUB_REPOSITORY (owner/name)}"
REGION="${REGION:-us-central1}"
SERVICE="${SERVICE:-durak-game}"
REPOSITORY="${REPOSITORY:-durak-game}"
PUBLIC_URL="${PUBLIC_URL:-}"
DEPLOYER_SA_NAME="${DEPLOYER_SA_NAME:-durak-deployer}"
POOL_ID="${POOL_ID:-github}"
PROVIDER_ID="${PROVIDER_ID:-durak-game}"
RUNTIME_SERVICE_ACCOUNT="${RUNTIME_SERVICE_ACCOUNT:-}"
ALLOW_DEFAULT_COMPUTE_SA="${ALLOW_DEFAULT_COMPUTE_SA:-false}"
GCLOUD_BIN="${GCLOUD_BIN:-gcloud}"
GH_BIN="${GH_BIN:-gh}"
# A new service account can take a few seconds to become visible to IAM policy updates.
RETRY_DELAY_SECONDS="${RETRY_DELAY_SECONDS:-5}"

DEPLOYER_SA="${DEPLOYER_SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"
WORKFLOW_FILE=".github/workflows/deploy.yml"

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

PROJECT_NUMBER="$("${GCLOUD_BIN}" projects describe "${PROJECT_ID}" --format='value(projectNumber)')"
if [[ ! "${PROJECT_NUMBER}" =~ ^[0-9]+$ ]]; then
  echo "Error: could not resolve project number for PROJECT_ID=${PROJECT_ID}" >&2
  exit 1
fi
REPOSITORY_ID="$("${GH_BIN}" api "repos/${GITHUB_REPOSITORY}" --jq .id)"
if [[ ! "${REPOSITORY_ID}" =~ ^[0-9]+$ ]]; then
  echo "Error: could not resolve the GitHub repository id of ${GITHUB_REPOSITORY}" >&2
  exit 1
fi
if [[ -z "${RUNTIME_SERVICE_ACCOUNT}" ]]; then
  RUNTIME_SERVICE_ACCOUNT="$("${GCLOUD_BIN}" run services describe "${SERVICE}" \
    --region "${REGION}" \
    --project "${PROJECT_ID}" \
    --format='value(spec.template.spec.serviceAccountName)' \
    2>/dev/null || true)"
fi
if [[ -z "${RUNTIME_SERVICE_ACCOUNT}" ]]; then
  echo "Error: could not read ${SERVICE}'s runtime identity; deploy the service once with the deploy script, or set RUNTIME_SERVICE_ACCOUNT." >&2
  exit 1
fi
if [[ "${RUNTIME_SERVICE_ACCOUNT}" == *-compute@developer.gserviceaccount.com && "${ALLOW_DEFAULT_COMPUTE_SA}" != "true" ]]; then
  echo "Error: ${SERVICE} runs as the default compute service account (${RUNTIME_SERVICE_ACCOUNT})." >&2
  echo "The deployer may act as the runtime identity, so this would give code merged to main that account's" >&2
  echo "broad project roles. Give the service its own identity first (scripts/setup-runtime-identity.sh and" >&2
  echo "README \"Runtime identity\"), then re-run; or set ALLOW_DEFAULT_COMPUTE_SA=true to accept the risk." >&2
  exit 1
fi

POOL="projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL_ID}"
PROVIDER="${POOL}/providers/${PROVIDER_ID}"
ATTRIBUTE_MAPPING="google.subject=assertion.sub,attribute.repository_id=assertion.repository_id,attribute.ref=assertion.ref,attribute.workflow_ref=assertion.workflow_ref"
ATTRIBUTE_CONDITION="assertion.repository_id == '${REPOSITORY_ID}' && assertion.ref == 'refs/heads/main' && assertion.workflow_ref.startsWith('${GITHUB_REPOSITORY}/${WORKFLOW_FILE}@')"

echo "==> Project: ${PROJECT_ID} (${PROJECT_NUMBER}), service ${SERVICE} in ${REGION}"
echo "==> GitHub repository: ${GITHUB_REPOSITORY} (id ${REPOSITORY_ID}), workflow ${WORKFLOW_FILE} on main"
echo "==> Deployer: ${DEPLOYER_SA}"
echo "==> Runtime identity it may deploy as: ${RUNTIME_SERVICE_ACCOUNT}"

echo "==> Enabling the APIs used to exchange GitHub tokens"
"${GCLOUD_BIN}" services enable iamcredentials.googleapis.com sts.googleapis.com --project "${PROJECT_ID}"

if "${GCLOUD_BIN}" iam service-accounts describe "${DEPLOYER_SA}" --project "${PROJECT_ID}" >/dev/null 2>&1; then
  echo "==> Deployer service account already exists"
else
  echo "==> Creating the deployer service account"
  "${GCLOUD_BIN}" iam service-accounts create "${DEPLOYER_SA_NAME}" \
    --display-name "Durak deployer (GitHub Actions)" \
    --description "Deploys durak-game from .github/workflows/deploy.yml on main" \
    --project "${PROJECT_ID}"
fi

echo "==> Granting the deployer what a release needs, scoped as narrowly as IAM allows"
retry "${GCLOUD_BIN}" run services add-iam-policy-binding "${SERVICE}" \
  --region "${REGION}" \
  --member "serviceAccount:${DEPLOYER_SA}" \
  --role roles/run.developer \
  --project "${PROJECT_ID}" \
  --quiet >/dev/null
retry "${GCLOUD_BIN}" projects add-iam-policy-binding "${PROJECT_ID}" \
  --member "serviceAccount:${DEPLOYER_SA}" \
  --role roles/run.viewer \
  --condition=None \
  --quiet >/dev/null
retry "${GCLOUD_BIN}" artifacts repositories add-iam-policy-binding "${REPOSITORY}" \
  --location "${REGION}" \
  --member "serviceAccount:${DEPLOYER_SA}" \
  --role roles/artifactregistry.writer \
  --project "${PROJECT_ID}" \
  --quiet >/dev/null
retry "${GCLOUD_BIN}" iam service-accounts add-iam-policy-binding "${RUNTIME_SERVICE_ACCOUNT}" \
  --member "serviceAccount:${DEPLOYER_SA}" \
  --role roles/iam.serviceAccountUser \
  --project "${PROJECT_ID}" \
  --quiet >/dev/null

if "${GCLOUD_BIN}" iam workload-identity-pools describe "${POOL_ID}" \
  --location global --project "${PROJECT_ID}" >/dev/null 2>&1; then
  echo "==> Workload identity pool ${POOL_ID} already exists"
else
  echo "==> Creating workload identity pool ${POOL_ID}"
  "${GCLOUD_BIN}" iam workload-identity-pools create "${POOL_ID}" \
    --location global \
    --display-name "GitHub Actions" \
    --project "${PROJECT_ID}"
fi

PROVIDER_ARGS=(
  --location global
  --workload-identity-pool "${POOL_ID}"
  --attribute-mapping "${ATTRIBUTE_MAPPING}"
  --attribute-condition "${ATTRIBUTE_CONDITION}"
  --project "${PROJECT_ID}"
)
if "${GCLOUD_BIN}" iam workload-identity-pools providers describe "${PROVIDER_ID}" \
  --location global --workload-identity-pool "${POOL_ID}" --project "${PROJECT_ID}" >/dev/null 2>&1; then
  echo "==> Updating provider ${PROVIDER_ID} (mapping and condition)"
  "${GCLOUD_BIN}" iam workload-identity-pools providers update-oidc "${PROVIDER_ID}" "${PROVIDER_ARGS[@]}"
else
  echo "==> Creating provider ${PROVIDER_ID} for GitHub's OIDC tokens"
  "${GCLOUD_BIN}" iam workload-identity-pools providers create-oidc "${PROVIDER_ID}" \
    --issuer-uri "https://token.actions.githubusercontent.com" \
    --display-name "${GITHUB_REPOSITORY} deploys" \
    "${PROVIDER_ARGS[@]}"
fi

echo "==> Letting this repository's deploy workflow act as the deployer"
retry "${GCLOUD_BIN}" iam service-accounts add-iam-policy-binding "${DEPLOYER_SA}" \
  --member "principalSet://iam.googleapis.com/${POOL}/attribute.repository_id/${REPOSITORY_ID}" \
  --role roles/iam.workloadIdentityUser \
  --project "${PROJECT_ID}" \
  --quiet >/dev/null

echo "==> Setting the repository variables the workflow reads"
"${GH_BIN}" variable set GCP_PROJECT_ID --body "${PROJECT_ID}" --repo "${GITHUB_REPOSITORY}"
"${GH_BIN}" variable set GCP_REGION --body "${REGION}" --repo "${GITHUB_REPOSITORY}"
"${GH_BIN}" variable set GCP_WORKLOAD_IDENTITY_PROVIDER --body "${PROVIDER}" --repo "${GITHUB_REPOSITORY}"
"${GH_BIN}" variable set GCP_DEPLOY_SERVICE_ACCOUNT --body "${DEPLOYER_SA}" --repo "${GITHUB_REPOSITORY}"
if [[ -n "${PUBLIC_URL}" ]]; then
  "${GH_BIN}" variable set PUBLIC_URL --body "${PUBLIC_URL%/}" --repo "${GITHUB_REPOSITORY}"
fi

echo
echo "Done. ${WORKFLOW_FILE} can now release main. Switch over from any other deployer (such as a"
echo "Cloud Build trigger on main) with merges to main frozen:"
echo "  1. Disable the other deployer. That stops only new builds: cancel or wait out the ones still"
echo "     queued or running, since each one ends by deploying its own image."
echo "  2. Release through ${WORKFLOW_FILE}: merge it, or run it from the Actions tab."
echo "  3. Confirm that release was the last write: scripts/check-release-handover.sh."
echo "  4. Resume merges."
echo "Details: README, \"Switching from the Cloud Build trigger\"."
