#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SMOKE_SCRIPT="${ROOT}/scripts/smoke-test.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d ' ' -f 1
  else
    shasum -a 256 "$1" | cut -d ' ' -f 1
  fi
}

# Writes a static directory like the real one: an entry module, one imported module, a stylesheet,
# and an index.html whose URLs carry each file's content version (as update-asset-versions.mjs does).
build() {
  local dir="$1" main="$2" logic="$3" css="$4"
  mkdir -p "${dir}/js" "${dir}/css"
  printf '%s\n' "${main}" >"${dir}/js/main.js"
  printf '%s\n' "${logic}" >"${dir}/js/logic.js"
  printf '%s\n' "${css}" >"${dir}/css/app.css"
  local v_main v_logic v_css
  v_main="$(sha256 "${dir}/js/main.js" | cut -c 1-12)"
  v_logic="$(sha256 "${dir}/js/logic.js" | cut -c 1-12)"
  v_css="$(sha256 "${dir}/css/app.css" | cut -c 1-12)"
  cat >"${dir}/index.html" <<HTML
<link rel="stylesheet" href="/css/app.css?v=${v_css}">
<script type="importmap">
{
  "imports": {
    "/js/logic.js": "/js/logic.js?v=${v_logic}"
  }
}
</script>
<link rel="modulepreload" href="/js/logic.js?v=${v_logic}">
<script type="module" src="/js/main.js?v=${v_main}"></script>
HTML
}

copy_site() {
  rm -rf "$2"
  cp -R "$1" "$2"
}

# Serves $SITE_DIR like the deployed site (/ is index.html; query strings are ignored) and
# /actuator/health from $HEALTH; records every URL it was asked for.
FAKE_CURL="${TMP_DIR}/curl"
cat >"${FAKE_CURL}" <<'FAKE'
#!/usr/bin/env bash
out=""
url=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    --max-time) shift 2 ;;
    -*) shift ;;
    *) url="$1"; shift ;;
  esac
done
echo "${url}" >>"${CALLS_FILE}"
path="${url#https://durak.example}"
path="${path%%\?*}"
case "${path}" in
  /actuator/health) printf '%s' "${HEALTH}" >"${out}" ;;
  /) cp "${SITE_DIR}/index.html" "${out}" 2>/dev/null || exit 22 ;;
  *) [[ -f "${SITE_DIR}${path}" ]] || exit 22; cp "${SITE_DIR}${path}" "${out}" ;;
esac
FAKE
chmod +x "${FAKE_CURL}"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

run_smoke() {
  local name="$1" checkout="$2" site="$3"
  shift 3
  env \
    CALLS_FILE="${TMP_DIR}/${name}.calls" \
    CURL_BIN="${FAKE_CURL}" \
    STATIC_DIR="${checkout}" \
    SITE_DIR="${site}" \
    HEALTH='{"status":"UP"}' \
    ATTEMPTS="2" \
    DELAY_SECONDS="0" \
    "$@" \
    bash "${SMOKE_SCRIPT}" "https://durak.example/" >"${TMP_DIR}/${name}.out" 2>"${TMP_DIR}/${name}.err"
}

expect_failure() {
  local name="$1" message="$2"
  shift 2
  if run_smoke "${name}" "$@"; then
    fail "${name}: the smoke test passed"
  fi
  grep -F -- "${message}" "${TMP_DIR}/${name}.err" >/dev/null \
    || fail "${name}: expected '${message}' in: $(cat "${TMP_DIR}/${name}.err")"
}

OLD="${TMP_DIR}/old"
NEW="${TMP_DIR}/new"
build "${OLD}" "console.log('main v1');" "export const rules = 1;" "body { color: black; }"
build "${NEW}" "console.log('main v2');" "export const rules = 1;" "body { color: black; }"

# The release under test is served: health, page and every versioned asset are checked.
copy_site "${NEW}" "${TMP_DIR}/site-ok"
run_smoke ok "${NEW}" "${TMP_DIR}/site-ok" || fail "smoke test failed although the new build is served: $(cat "${TMP_DIR}/ok.err")"
for url in "https://durak.example/actuator/health" "https://durak.example/" \
    "https://durak.example/js/main.js?v=" "https://durak.example/js/logic.js?v=" "https://durak.example/css/app.css?v="; do
  grep -F -- "${url}" "${TMP_DIR}/ok.calls" >/dev/null || fail "not checked: ${url} (or the trailing slash was kept)"
done
grep -F "its 3 versioned assets" "${TMP_DIR}/ok.out" >/dev/null || fail "expected three distinct assets: $(cat "${TMP_DIR}/ok.out")"

expect_failure down "health is not UP" "${NEW}" "${TMP_DIR}/site-ok" HEALTH='{"status":"DOWN"}'
[[ "$(grep -c 'actuator/health' "${TMP_DIR}/down.calls")" == "2" ]] || fail "expected one check per attempt"
grep -F "did not pass the smoke test: health is not UP" "${TMP_DIR}/down.err" >/dev/null || fail "missing final error"

# The previous build is still served.
copy_site "${OLD}" "${TMP_DIR}/site-old"
expect_failure previous "the home page is not this checkout's index.html" "${NEW}" "${TMP_DIR}/site-old"

# The page is current, but its entry script is missing or still the old file.
copy_site "${NEW}" "${TMP_DIR}/site-missing"
rm "${TMP_DIR}/site-missing/js/main.js"
expect_failure missing-js "/js/main.js?v=" "${NEW}" "${TMP_DIR}/site-missing"
grep -F "did not load" "${TMP_DIR}/missing-js.err" >/dev/null || fail "missing-js: expected a load failure"
copy_site "${NEW}" "${TMP_DIR}/site-stale"
cp "${OLD}/js/main.js" "${TMP_DIR}/site-stale/js/main.js"
expect_failure stale-js "does not serve this checkout's /js/main.js" "${NEW}" "${TMP_DIR}/site-stale"

# A change confined to an imported module leaves main.js, and its hash, untouched.
MODULE="${TMP_DIR}/module-change"
build "${MODULE}" "console.log('main v2');" "export const rules = 2;" "body { color: black; }"
[[ "$(grep -o 'main.js?v=[0-9a-f]*' "${MODULE}/index.html")" == "$(grep -o 'main.js?v=[0-9a-f]*' "${NEW}/index.html")" ]] \
  || fail "fixture: a module-only change must keep main.js's version"
# Only the import map and preload differ, so a check of main.js alone would pass here.
expect_failure module-only-page "the home page is not this checkout's index.html" "${MODULE}" "${TMP_DIR}/site-ok"
copy_site "${MODULE}" "${TMP_DIR}/site-module-stale"
cp "${NEW}/js/logic.js" "${TMP_DIR}/site-module-stale/js/logic.js"
expect_failure module-only-file "does not serve this checkout's /js/logic.js" "${MODULE}" "${TMP_DIR}/site-module-stale"

# A stale stylesheet fails too.
copy_site "${NEW}" "${TMP_DIR}/site-css"
printf 'body { color: red; }\n' >"${TMP_DIR}/site-css/css/app.css"
expect_failure stale-css "does not serve this checkout's /css/app.css" "${NEW}" "${TMP_DIR}/site-css"

# A checkout without the module block can't be checked at all.
BROKEN="${TMP_DIR}/broken"
copy_site "${NEW}" "${BROKEN}"
printf '<p>no scripts</p>\n' >"${BROKEN}/index.html"
expect_failure no-import-map "has no import map or no versioned /js/main.js" "${BROKEN}" "${TMP_DIR}/site-ok"

echo "Smoke test contract tests passed."
