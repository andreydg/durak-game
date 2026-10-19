#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GATE_SCRIPT="${ROOT}/scripts/release-gate.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

HEAD_SHA="1111111111111111111111111111111111111111"
OLD_SHA="2222222222222222222222222222222222222222"

# Answers like the GitHub API: main's head is $HEAD, and $CI_RUNS (a JSON array) are the CI push
# runs for the commit asked about. Applies the --jq filter with jq, as gh does.
FAKE_GH="${TMP_DIR}/gh"
cat >"${FAKE_GH}" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${CALLS_FILE}"
filter=""
path=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --jq) filter="$2"; shift 2 ;;
    -X | -f) shift 2 ;;
    api) shift ;;
    *) path="$1"; shift ;;
  esac
done
case "${path}" in
  */git/ref/heads/main) body="{\"object\":{\"sha\":\"${HEAD}\"}}" ;;
  */actions/workflows/ci.yml/runs) body="{\"workflow_runs\":${CI_RUNS}}" ;;
  *) echo "unexpected API call: ${path}" >&2; exit 1 ;;
esac
jq -r "${filter}" <<<"${body}"
FAKE
chmod +x "${FAKE_GH}"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

run_gate() {
  local name="$1"
  shift
  env \
    CALLS_FILE="${TMP_DIR}/${name}.calls" \
    GH_BIN="${FAKE_GH}" \
    GITHUB_OUTPUT="${TMP_DIR}/${name}.output" \
    GITHUB_REPOSITORY="andreydg/durak-game" \
    HEAD="${HEAD_SHA}" \
    SHA="${HEAD_SHA}" \
    "$@" \
    bash "${GATE_SCRIPT}" >"${TMP_DIR}/${name}.out" 2>"${TMP_DIR}/${name}.err"
}

run_json() {
  printf '{"head_sha":"%s","run_number":%s,"status":"%s","conclusion":%s}' "$1" "$2" "$3" "$4"
}
PASSED="[$(run_json "${HEAD_SHA}" 7 completed '"success"')]"

# The head of main with a successful CI push run is released.
run_gate passed CI_RUNS="${PASSED}" || fail "a passed head was not released"
grep -Fx "release=true" "${TMP_DIR}/passed.output" >/dev/null || fail "missing release=true output"
grep -F "head_sha=${HEAD_SHA}" "${TMP_DIR}/passed.calls" >/dev/null || fail "CI was not asked about this exact commit"
grep -F "event=push" "${TMP_DIR}/passed.calls" >/dev/null || fail "pull request runs must not count"
grep -F "branch=main" "${TMP_DIR}/passed.calls" >/dev/null || fail "runs on other branches must not count"

# A commit that is no longer the head is skipped quietly; the newer commit's release deploys.
run_gate stale SHA="${OLD_SHA}" CI_RUNS="[$(run_json "${OLD_SHA}" 6 completed '"success"')]" \
  || fail "a stale commit should be skipped, not fail the run"
grep -Fx "release=false" "${TMP_DIR}/stale.output" >/dev/null || fail "a stale commit was released"
if grep -F "actions/workflows" "${TMP_DIR}/stale.calls" >/dev/null; then
  fail "CI need not be checked for a stale commit"
fi

# A manual run while the head's CI is pending, failed, cancelled, or missing releases nothing.
for case in \
    "pending:[$(run_json "${HEAD_SHA}" 8 in_progress null)]" \
    "queued:[$(run_json "${HEAD_SHA}" 8 queued null)]" \
    "failed:[$(run_json "${HEAD_SHA}" 8 completed '"failure"')]" \
    "cancelled:[$(run_json "${HEAD_SHA}" 8 completed '"cancelled"')]" \
    "missing:[]"; do
  name="${case%%:*}"
  if run_gate "${name}" CI_RUNS="${case#*:}"; then
    fail "released the head although its CI is ${name}"
  fi
  grep -Fx "release=false" "${TMP_DIR}/${name}.output" >/dev/null || fail "${name}: missing release=false output"
  grep -F "CI has not passed for ${HEAD_SHA}" "${TMP_DIR}/${name}.out" >/dev/null || fail "${name}: missing error"
done

# The latest run decides: a re-run in progress, or a later failing run, blocks an earlier success.
if run_gate rerun CI_RUNS="[$(run_json "${HEAD_SHA}" 7 completed '"success"'),$(run_json "${HEAD_SHA}" 9 in_progress null)]"; then
  fail "released while a later CI run for the head was still running"
fi
run_gate later-pass CI_RUNS="[$(run_json "${HEAD_SHA}" 9 completed '"success"'),$(run_json "${HEAD_SHA}" 7 completed '"failure"')]" \
  || fail "an earlier failure blocked a later success"

# Runs for other commits are ignored even if the API returned them.
if run_gate other-commit CI_RUNS="[$(run_json "${OLD_SHA}" 9 completed '"success"')]"; then
  fail "another commit's CI run released the head"
fi

if run_gate short-sha SHA="1111111"; then
  fail "accepted an abbreviated commit id"
fi

echo "Release gate contract tests passed."
