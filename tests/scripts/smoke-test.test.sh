#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SMOKE_SCRIPT="${ROOT}/scripts/smoke-test.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

cat >"${TMP_DIR}/index.html" <<'HTML'
<link rel="modulepreload" href="/js/logic.js?v=111111111111">
<script type="module" src="/js/main.js?v=abcdef123456"></script>
HTML

# Answers /actuator/health with $HEALTH and / with $PAGE; records every URL it was asked for.
FAKE_CURL="${TMP_DIR}/curl"
cat >"${FAKE_CURL}" <<'FAKE'
#!/usr/bin/env bash
url="${*: -1}"
echo "${url}" >>"${CALLS_FILE}"
case "${url}" in
  */actuator/health) printf '%s' "${HEALTH}" ;;
  */) printf '%s' "${PAGE}" ;;
  *) exit 22 ;;
esac
FAKE
chmod +x "${FAKE_CURL}"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

run_smoke() {
  local calls_file="$1"
  shift
  env \
    CALLS_FILE="${calls_file}" \
    CURL_BIN="${FAKE_CURL}" \
    INDEX_HTML="${TMP_DIR}/index.html" \
    ATTEMPTS="2" \
    DELAY_SECONDS="0" \
    "$@" \
    bash "${SMOKE_SCRIPT}" "https://durak.example/"
}

NEW_PAGE='<script type="module" src="/js/main.js?v=abcdef123456"></script>'
OLD_PAGE='<script type="module" src="/js/main.js?v=000000000000"></script>'

run_smoke "${TMP_DIR}/ok.calls" HEALTH='{"status":"UP"}' PAGE="${NEW_PAGE}" >"${TMP_DIR}/ok.out" \
  || fail "smoke test failed although the new build is UP"
grep -F "https://durak.example/actuator/health" "${TMP_DIR}/ok.calls" >/dev/null \
  || fail "health endpoint was not checked (or the trailing slash was not trimmed)"

if run_smoke "${TMP_DIR}/old.calls" HEALTH='{"status":"UP"}' PAGE="${OLD_PAGE}" \
    >"${TMP_DIR}/old.out" 2>"${TMP_DIR}/old.err"; then
  fail "smoke test passed while the previous build was still being served"
fi
[[ "$(grep -c 'actuator/health' "${TMP_DIR}/old.calls")" == "2" ]] || fail "expected one check per attempt"

if run_smoke "${TMP_DIR}/down.calls" HEALTH='{"status":"DOWN"}' PAGE="${NEW_PAGE}" \
    >"${TMP_DIR}/down.out" 2>"${TMP_DIR}/down.err"; then
  fail "smoke test passed while health was DOWN"
fi
grep -F "did not pass the smoke test" "${TMP_DIR}/down.err" >/dev/null || fail "missing failure message"

echo "Smoke test contract tests passed."
