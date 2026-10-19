#!/usr/bin/env bash
set -euo pipefail

# Checks a deployed Durak site after a release, retrying while traffic settles:
#   - the health endpoint (which reads the game store) reports UP;
#   - the home page is byte for byte this checkout's index.html, so its import map, module
#     preloads and entry script are this build's;
#   - every versioned stylesheet and module that page references (each import map target and
#     preload, and the entry script) serves exactly this checkout's file.
# Checking main.js alone is not enough: a change confined to one module changes only that
# module's hash in the import map, and a stale or missing file can sit behind a current page.
#
# Usage:
#   ./scripts/smoke-test.sh https://durak.andreyg.com
#
# Optional env:
#   ATTEMPTS=12 DELAY_SECONDS=5   (retries while traffic settles)
#   STATIC_DIR=src/main/resources/static  CURL_BIN=curl

BASE_URL="${1:?Usage: smoke-test.sh BASE_URL}"
BASE_URL="${BASE_URL%/}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATIC_DIR="${STATIC_DIR:-${ROOT}/src/main/resources/static}"
ATTEMPTS="${ATTEMPTS:-12}"
DELAY_SECONDS="${DELAY_SECONDS:-5}"
CURL_BIN="${CURL_BIN:-curl}"
INDEX_HTML="${STATIC_DIR}/index.html"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d ' ' -f 1
  else
    shasum -a 256 "$1" | cut -d ' ' -f 1
  fi
}

# Every versioned URL in the page, once: stylesheets, import map targets, preloads, entry script.
ASSETS="$(grep -o -E '/(css|js)/[A-Za-z0-9._-]+\.(css|js)\?v=[0-9a-f]+' "${INDEX_HTML}" | sort -u || true)"
if ! grep -q -F '<script type="importmap">' "${INDEX_HTML}" || ! grep -q -E '^/js/main\.js\?v=' <<<"${ASSETS}"; then
  echo "Error: ${INDEX_HTML} has no import map or no versioned /js/main.js" >&2
  exit 1
fi
ASSET_COUNT="$(wc -l <<<"${ASSETS}" | tr -d ' ')"

fetch() {
  "${CURL_BIN}" -fsS --max-time 15 -o "$2" "$1" 2>/dev/null
}

# Prints what is wrong with the deployed site, or nothing when it serves this checkout.
problem() {
  local body="${WORK_DIR}/body" asset file
  if ! fetch "${BASE_URL}/actuator/health" "${body}" || ! grep -q -F '"status":"UP"' "${body}"; then
    echo "health is not UP"
    return
  fi
  if ! fetch "${BASE_URL}/" "${body}"; then
    echo "the home page did not load"
    return
  fi
  if [[ "$(sha256 "${body}")" != "$(sha256 "${INDEX_HTML}")" ]]; then
    echo "the home page is not this checkout's index.html (an older build, or another import map)"
    return
  fi
  while IFS= read -r asset; do
    file="${STATIC_DIR}${asset%%\?*}"
    if ! fetch "${BASE_URL}${asset}" "${body}"; then
      echo "${asset} did not load"
      return
    fi
    if [[ "$(sha256 "${body}")" != "$(sha256 "${file}")" ]]; then
      echo "${asset} does not serve this checkout's ${asset%%\?*}"
      return
    fi
  done <<<"${ASSETS}"
}

for attempt in $(seq 1 "${ATTEMPTS}"); do
  wrong="$(problem)"
  if [[ -z "${wrong}" ]]; then
    echo "Smoke test passed: ${BASE_URL} is UP and serves this checkout's index.html and its ${ASSET_COUNT} versioned assets"
    exit 0
  fi
  echo "Attempt ${attempt}/${ATTEMPTS}: ${wrong}" >&2
  if [[ "${attempt}" -lt "${ATTEMPTS}" ]]; then
    sleep "${DELAY_SECONDS}"
  fi
done

echo "Error: ${BASE_URL} did not pass the smoke test: ${wrong}" >&2
exit 1
