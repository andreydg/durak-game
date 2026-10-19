#!/usr/bin/env bash
set -euo pipefail

# Read-only check for the one-time switch from a Cloud Build trigger to the Deploy workflow
# (README "Switching from the Cloud Build trigger"). Run it before resuming merges. It confirms
# that the Deploy workflow's release is the last write to the service and that the trigger can no
# longer write:
#   1. the trigger is disabled, so it starts no new builds;
#   2. none of its builds is still queued or running: disabling doesn't stop those, and each one
#      ends by pointing the service at its own image;
#   3. the service runs the image the Deploy workflow built for the head of main, its latest
#      revision is ready, and that revision has all the traffic.
# Changes nothing; exits non-zero, saying what to do next, until all three hold.
#
# Usage:
#   PROJECT_ID=andreyg-main REGION=us-west1 GITHUB_REPOSITORY=andreydg/durak-game \
#     LEGACY_TRIGGER=rmgpgab-durak-game-us-west1-andreydg-durak-game--maubo \
#     ./scripts/check-release-handover.sh
#
# Optional env:
#   SERVICE=durak-game  REPOSITORY=durak-game  TRIGGER_REGION=global  BRANCH=main
#   GCLOUD_BIN=gcloud  GH_BIN=gh

: "${PROJECT_ID:?Set PROJECT_ID (your GCP project id)}"
: "${REGION:?Set REGION (the Cloud Run region)}"
: "${GITHUB_REPOSITORY:?Set GITHUB_REPOSITORY (owner/name)}"
: "${LEGACY_TRIGGER:?Set LEGACY_TRIGGER (the Cloud Build trigger that used to deploy main)}"
SERVICE="${SERVICE:-durak-game}"
REPOSITORY="${REPOSITORY:-durak-game}"
TRIGGER_REGION="${TRIGGER_REGION:-global}"
BRANCH="${BRANCH:-main}"
GCLOUD_BIN="${GCLOUD_BIN:-gcloud}"
GH_BIN="${GH_BIN:-gh}"
ERRORS="$(mktemp)"
trap 'rm -f "${ERRORS}"' EXIT

problems=0
ok() {
  echo "OK    $*"
}
problem() {
  echo "FAIL  $*"
  problems=$((problems + 1))
}

trigger="$("${GCLOUD_BIN}" builds triggers describe "${LEGACY_TRIGGER}" \
  --region "${TRIGGER_REGION}" --project "${PROJECT_ID}" --format=json)"
trigger_id="$(jq -r '.id // empty' <<<"${trigger}")"
if [[ -z "${trigger_id}" ]]; then
  echo "Error: could not read the id of trigger ${LEGACY_TRIGGER}" >&2
  exit 1
fi
if [[ "$(jq -r '.disabled // false' <<<"${trigger}")" == "true" ]]; then
  ok "trigger ${LEGACY_TRIGGER} is disabled"
else
  problem "trigger ${LEGACY_TRIGGER} is enabled: disable it (README \"Switching from the Cloud Build trigger\")"
fi

# When nothing matches, gcloud warns on stderr that the filter key is unknown; only a failure counts.
if ! ongoing="$("${GCLOUD_BIN}" builds list --ongoing --filter="buildTriggerId=${trigger_id}" \
    --region "${TRIGGER_REGION}" --project "${PROJECT_ID}" --format='value(id)' 2>"${ERRORS}")"; then
  cat "${ERRORS}" >&2
  echo "Error: could not list the trigger's builds" >&2
  exit 1
fi
if [[ -z "${ongoing}" ]]; then
  ok "no build from the trigger is queued or running"
else
  problem "builds from the trigger are still queued or running; each one would replace the service's image:"
  while IFS= read -r build; do
    echo "        gcloud builds cancel ${build} --region ${TRIGGER_REGION} --project ${PROJECT_ID}"
  done <<<"${ongoing}"
fi

head="$("${GH_BIN}" api "repos/${GITHUB_REPOSITORY}/git/ref/heads/${BRANCH}" --jq '.object.sha')"
expected="${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPOSITORY}/${SERVICE}:${head}"
service="$("${GCLOUD_BIN}" run services describe "${SERVICE}" --region "${REGION}" --project "${PROJECT_ID}" --format=json)"
image="$(jq -r '.spec.template.spec.containers[0].image' <<<"${service}")"
ready="$(jq -r '.status.latestReadyRevisionName == .status.latestCreatedRevisionName' <<<"${service}")"
latest_percent="$(jq -r '[.status.traffic[]? | select(.latestRevision == true) | .percent] | add // 0' <<<"${service}")"
if [[ "${image}" == "${expected}" && "${ready}" == "true" && "${latest_percent}" == "100" ]]; then
  ok "${SERVICE} serves the Deploy workflow's image for ${head}, with all traffic"
else
  problem "${SERVICE} runs ${image} (latest revision ready: ${ready}, its traffic: ${latest_percent}%),"
  echo "        not ${expected} with all traffic. Once nothing above is left, run the Deploy workflow"
  echo "        for the head of ${BRANCH} (Actions > Deploy > Run workflow) and run this check again."
fi

if [[ "${problems}" -gt 0 ]]; then
  echo "The handover is not complete; keep merges to ${BRANCH} frozen."
  exit 1
fi
echo "The Deploy workflow is the only deployer and released the head of ${BRANCH} last. Merges can resume."
