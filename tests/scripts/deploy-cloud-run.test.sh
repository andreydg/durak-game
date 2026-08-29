#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOY_SCRIPT="${ROOT}/scripts/deploy-cloud-run.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

FAKE_GCLOUD="${TMP_DIR}/gcloud"
cat >"${FAKE_GCLOUD}" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail

(
  printf '%s' "$1"
  shift
  printf '\t%s' "$@"
  printf '\n'
) >>"${CALLS_FILE}"

case "${1:-}:${2:-}:${3:-}" in
  projects:describe:527294552477)
    echo "527294552477"
    ;;
  projects:describe:secret-project)
    echo "777777777777"
    ;;
  projects:describe:andreyg-main)
    echo "629903314059"
    ;;
  run:services:describe)
    if [[ "${FAIL_SERVICE_DESCRIBE:-false}" == "true" ]]; then
      exit 1
    fi
    echo "629903314059-compute@developer.gserviceaccount.com"
    ;;
  secrets:describe:*)
    if [[ "${FAIL_SECRET_DESCRIBE:-false}" == "true" ]]; then
      exit 1
    fi
    ;;
  secrets:versions:describe)
    if [[ "${FAIL_SECRET_VERSION:-false}" == "true" ]]; then
      exit 1
    fi
    ;;
  artifacts:repositories:describe)
    exit 0
    ;;
esac
FAKE
chmod +x "${FAKE_GCLOUD}"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_contains() {
  local file="$1"
  local expected="$2"
  grep -F -- "${expected}" "${file}" >/dev/null || fail "${file} did not contain: ${expected}"
}

assert_not_contains() {
  local file="$1"
  local unexpected="$2"
  if grep -F -- "${unexpected}" "${file}" >/dev/null; then
    fail "${file} unexpectedly contained: ${unexpected}"
  fi
}

run_deploy() {
  local calls_file="$1"
  shift
  env \
    CALLS_FILE="${calls_file}" \
    GCLOUD_BIN="${FAKE_GCLOUD}" \
    PROJECT_ID="andreyg-main" \
    REGION="us-west1" \
    CONFIGURE_FIRESTORE_TTL="false" \
    "$@" \
    bash "${DEPLOY_SCRIPT}"
}

DEFAULT_CALLS="${TMP_DIR}/default.calls"
run_deploy "${DEFAULT_CALLS}" >"${TMP_DIR}/default.out"
assert_contains "${DEFAULT_CALLS}" $'services\tenable\tsecretmanager.googleapis.com\t--project\t527294552477'
assert_contains "${DEFAULT_CALLS}" $'secrets\tdescribe\tgemini-api-key\t--project\t527294552477'
assert_contains "${DEFAULT_CALLS}" $'secrets\tversions\tdescribe\tlatest\t--secret\tgemini-api-key\t--project\t527294552477'
assert_contains "${DEFAULT_CALLS}" $'--member\tserviceAccount:629903314059-compute@developer.gserviceaccount.com'
assert_contains "${DEFAULT_CALLS}" $'--role\troles/secretmanager.secretAccessor'
assert_contains "${DEFAULT_CALLS}" $'--service-account\t629903314059-compute@developer.gserviceaccount.com'
assert_contains "${DEFAULT_CALLS}" $'--update-env-vars\tAUTOPLAY_GEMINI_MODEL=gemini-3.7-flash'
assert_contains "${DEFAULT_CALLS}" $'--update-secrets\tGEMINI_API_KEY=projects/527294552477/secrets/gemini-api-key:latest'
assert_not_contains "${DEFAULT_CALLS}" $'secrets\tversions\taccess'

CUSTOM_CALLS="${TMP_DIR}/custom.calls"
run_deploy "${CUSTOM_CALLS}" \
  GEMINI_SECRET="alternate-key" \
  GEMINI_SECRET_PROJECT="secret-project" \
  GEMINI_SECRET_VERSION="7" \
  RUNTIME_SERVICE_ACCOUNT="durak-runtime@andreyg-main.iam.gserviceaccount.com" \
  AUTOPLAY_GEMINI_MODEL="gemini-3.7-flash" \
  >"${TMP_DIR}/custom.out"
assert_contains "${CUSTOM_CALLS}" $'--member\tserviceAccount:durak-runtime@andreyg-main.iam.gserviceaccount.com'
assert_contains "${CUSTOM_CALLS}" $'--update-secrets\tGEMINI_API_KEY=projects/777777777777/secrets/alternate-key:7'
assert_not_contains "${CUSTOM_CALLS}" $'run\tservices\tdescribe'

FALLBACK_CALLS="${TMP_DIR}/fallback.calls"
run_deploy "${FALLBACK_CALLS}" FAIL_SERVICE_DESCRIBE="true" >"${TMP_DIR}/fallback.out"
assert_contains "${FALLBACK_CALLS}" $'projects\tdescribe\tandreyg-main'
assert_contains "${FALLBACK_CALLS}" $'--service-account\t629903314059-compute@developer.gserviceaccount.com'

MISSING_CALLS="${TMP_DIR}/missing.calls"
if run_deploy "${MISSING_CALLS}" FAIL_SECRET_DESCRIBE="true" >"${TMP_DIR}/missing.out" 2>"${TMP_DIR}/missing.err"; then
  fail "deployment unexpectedly continued when the secret could not be described"
fi
assert_contains "${TMP_DIR}/missing.err" "could not verify projects/527294552477/secrets/gemini-api-key"
assert_not_contains "${MISSING_CALLS}" $'builds\tsubmit'
assert_not_contains "${MISSING_CALLS}" $'run\tdeploy'

MISSING_VERSION_CALLS="${TMP_DIR}/missing-version.calls"
if run_deploy "${MISSING_VERSION_CALLS}" FAIL_SECRET_VERSION="true" >"${TMP_DIR}/missing-version.out" 2>"${TMP_DIR}/missing-version.err"; then
  fail "deployment unexpectedly continued when the secret version could not be described"
fi
assert_contains "${TMP_DIR}/missing-version.err" "could not verify projects/527294552477/secrets/gemini-api-key:latest"
assert_not_contains "${MISSING_VERSION_CALLS}" $'secrets\tadd-iam-policy-binding'
assert_not_contains "${MISSING_VERSION_CALLS}" $'builds\tsubmit'
assert_not_contains "${MISSING_VERSION_CALLS}" $'run\tdeploy'

echo "Secret Manager deployment contract tests passed."
