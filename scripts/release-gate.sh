#!/usr/bin/env bash
set -euo pipefail

# Decides whether the Deploy workflow may release a commit. The commit must still be the head of
# main, and the CI workflow's latest run for that exact commit on a push to main must have passed.
# The automatic release starts from that CI run; manual runs start from nothing, so this is what
# gates them. The workflow runs it before touching Google Cloud and again before deploying.
#
#   release=true   the commit is the head of main and CI passed for it
#   release=false  main has moved on; the newer commit's own release deploys it (exit 0)
#   exit 1         CI for the head is missing, still running, or failed: nothing is released
#
# The decision is printed and, when GITHUB_OUTPUT is set, written there as the step's output.
#
# Usage:
#   SHA=<full commit id> GITHUB_REPOSITORY=owner/name GH_TOKEN=... ./scripts/release-gate.sh
#
# Optional env:
#   BRANCH=main  CI_WORKFLOW=ci.yml  GH_BIN=gh

: "${SHA:?Set SHA (the commit to release)}"
: "${GITHUB_REPOSITORY:?Set GITHUB_REPOSITORY (owner/name)}"
BRANCH="${BRANCH:-main}"
CI_WORKFLOW="${CI_WORKFLOW:-ci.yml}"
GH_BIN="${GH_BIN:-gh}"

if [[ ! "${SHA}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Error: SHA must be a full 40-character commit id, got '${SHA}'" >&2
  exit 1
fi

decide() {
  echo "release=$1"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    echo "release=$1" >>"${GITHUB_OUTPUT}"
  fi
}

head="$("${GH_BIN}" api "repos/${GITHUB_REPOSITORY}/git/ref/heads/${BRANCH}" --jq '.object.sha')"
if [[ "${head}" != "${SHA}" ]]; then
  echo "::notice::${BRANCH} moved on to ${head}; leaving ${SHA} undeployed (the newer commit's release deploys it)."
  decide false
  exit 0
fi

# A re-run updates its run in place, so the latest run's state is CI's current verdict.
ci="$("${GH_BIN}" api -X GET "repos/${GITHUB_REPOSITORY}/actions/workflows/${CI_WORKFLOW}/runs" \
  -f head_sha="${SHA}" -f event=push -f branch="${BRANCH}" -f per_page=100 \
  --jq "[.workflow_runs[] | select(.head_sha == \"${SHA}\")] | max_by(.run_number)
        | if . == null then \"none\" else \"\(.status) \(.conclusion)\" end")"
if [[ "${ci}" != "completed success" ]]; then
  echo "::error::CI has not passed for ${SHA} on ${BRANCH} (latest run: ${ci}); not releasing it."
  decide false
  exit 1
fi
echo "CI passed for ${SHA}, the head of ${BRANCH}."
decide true
