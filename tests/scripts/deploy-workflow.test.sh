#!/usr/bin/env bash
set -euo pipefail

# Static checks on the properties of .github/workflows/deploy.yml that releases depend on. The
# workflow only ever runs on main, so a regression here would otherwise surface in production.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="${ROOT}/.github/workflows/deploy.yml"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

# Lines of the top-level section $1 (e.g. "concurrency"), without its header.
section() {
  awk -v name="$1:" '$0 == name {inside = 1; next} inside && /^[^ ]/ {exit} inside' "${WORKFLOW}"
}

# Lines of the step named $1, from its "- name:" line up to the next step.
step() {
  awk -v name="      - name: $1" '
    $0 == name {inside = 1; print; next}
    inside && /^      - / {exit}
    inside {print}' "${WORKFLOW}"
}

# Line number of the step named $1.
step_line() {
  grep -n -x -F -- "      - name: $1" "${WORKFLOW}" | cut -d: -f1
}

GATE="Check that this commit is the head of main and passed CI"
GATE_AGAIN="Check again that this commit is the head of main"
BEFORE_DEPLOY_STEPS=("Authenticate to Google Cloud" "Set up gcloud" "Build and push the image")
DEPLOY_STEPS=("Deploy" "Check the service serves this image with all traffic" "Smoke test")

# One release at a time; a waiting release is never cancelled by a later arrival (a CI re-run of an
# older commit used to evict the head's pending release this way).
concurrency="$(section concurrency)"
grep -x -F '  group: production' <<<"${concurrency}" >/dev/null || fail "releases must share the production group"
grep -x -F '  cancel-in-progress: false' <<<"${concurrency}" >/dev/null || fail "a running release must not be cancelled"
grep -x -F '  queue: max' <<<"${concurrency}" >/dev/null || fail "waiting releases must queue, not replace each other"

# Manual runs are accepted from main only, and the automatic path only for CI's successful push runs.
grep -F "(github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main')" "${WORKFLOW}" >/dev/null \
  || fail "workflow_dispatch must be limited to main"
grep -F "(github.event.workflow_run.conclusion == 'success' && github.event.workflow_run.event == 'push')" \
  "${WORKFLOW}" >/dev/null || fail "only successful CI push runs may start a release"
grep -x -F '  actions: read' <<<"$(section permissions)" >/dev/null || fail "the gate needs actions: read to see CI runs"

# The gate (head of main + CI passed for the exact commit) runs before anything touches Google
# Cloud, and again after the build, right before deploying.
for name in "${GATE}" "${GATE_AGAIN}"; do
  [[ -n "$(step_line "${name}")" ]] || fail "missing step: ${name}"
  step "${name}" | grep -F 'run: ./scripts/release-gate.sh' >/dev/null || fail "${name} must run the release gate"
  step "${name}" | grep -F 'GH_TOKEN: ${{ github.token }}' >/dev/null || fail "${name} needs the token"
done
step "${GATE_AGAIN}" | grep -F "if: steps.gate.outputs.release == 'true'" >/dev/null \
  || fail "the second gate runs only after the first passed"

previous="$(step_line "${GATE}")"
for name in "${BEFORE_DEPLOY_STEPS[@]}"; do
  line="$(step_line "${name}")"
  [[ -n "${line}" && "${line}" -gt "${previous}" ]] || fail "${name} must come after the first gate"
  step "${name}" | grep -F "if: steps.gate.outputs.release == 'true'" >/dev/null || fail "${name} must wait for the gate"
  previous="${line}"
done
[[ "$(step_line "${GATE_AGAIN}")" -gt "${previous}" ]] || fail "the second gate must follow the build"
previous="$(step_line "${GATE_AGAIN}")"
for name in "${DEPLOY_STEPS[@]}"; do
  line="$(step_line "${name}")"
  [[ -n "${line}" && "${line}" -gt "${previous}" ]] || fail "${name} must come after the second gate"
  step "${name}" | grep -F "if: steps.gate-again.outputs.release == 'true'" >/dev/null \
    || fail "${name} must wait for the second gate"
  previous="${line}"
done

# The token reaches only the gate steps, not the image build or the deploy script.
[[ "$(grep -c -F 'GH_TOKEN:' "${WORKFLOW}")" == "2" ]] || fail "GH_TOKEN must be set on the two gate steps only"

echo "Deploy workflow contract tests passed."
