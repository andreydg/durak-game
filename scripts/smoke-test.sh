#!/usr/bin/env bash
set -euo pipefail

# Checks a deployed Durak site after a release: the health endpoint (which reads the game store)
# reports UP, and the home page loads the frontend entry script with the content hash from this
# checkout's index.html, i.e. the new build is the one being served.
#
# Usage:
#   ./scripts/smoke-test.sh https://durak.andreyg.com
#
# Optional env:
#   ATTEMPTS=12 DELAY_SECONDS=5   (retries while traffic settles)

BASE_URL="${1:?Usage: smoke-test.sh BASE_URL}"
BASE_URL="${BASE_URL%/}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INDEX_HTML="${INDEX_HTML:-${ROOT}/src/main/resources/static/index.html}"
ATTEMPTS="${ATTEMPTS:-12}"
DELAY_SECONDS="${DELAY_SECONDS:-5}"
CURL_BIN="${CURL_BIN:-curl}"

ENTRY="$(grep -o -E '/js/main\.js\?v=[0-9a-f]+' "${INDEX_HTML}" | head -n 1 || true)"
if [[ -z "${ENTRY}" ]]; then
  echo "Error: no versioned /js/main.js in ${INDEX_HTML}" >&2
  exit 1
fi

for attempt in $(seq 1 "${ATTEMPTS}"); do
  health="$("${CURL_BIN}" -fsS --max-time 15 "${BASE_URL}/actuator/health" 2>/dev/null || true)"
  page="$("${CURL_BIN}" -fsS --max-time 15 "${BASE_URL}/" 2>/dev/null || true)"
  if [[ "${health}" == *'"status":"UP"'* && "${page}" == *"${ENTRY}"* ]]; then
    echo "Smoke test passed: ${BASE_URL} is UP and serves ${ENTRY}"
    exit 0
  fi
  echo "Attempt ${attempt}/${ATTEMPTS}: ${BASE_URL} is not yet UP with ${ENTRY}" >&2
  if [[ "${attempt}" -lt "${ATTEMPTS}" ]]; then
    sleep "${DELAY_SECONDS}"
  fi
done

echo "Error: ${BASE_URL} did not pass the smoke test (health UP and ${ENTRY} on the home page)" >&2
exit 1
