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

if [[ "${1:-}" == "projects" && "${2:-}" == "describe" \
    && "${FAIL_PROJECT_DESCRIBE:-}" == "${3:-}" ]]; then
  exit 1
fi

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
  firestore:fields:ttls)
    if [[ "${FAIL_TTL_UPDATE:-false}" == "true" ]]; then
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
assert_contains "${DEFAULT_CALLS}" $'services\tenable\tsecretmanager.googleapis.com\t--project\tandreyg-main'
assert_contains "${DEFAULT_CALLS}" $'secrets\tdescribe\tgemini-api-key\t--project\tandreyg-main'
assert_contains "${DEFAULT_CALLS}" $'secrets\tversions\tdescribe\tlatest\t--secret\tgemini-api-key\t--project\tandreyg-main'
assert_contains "${DEFAULT_CALLS}" $'--member\tserviceAccount:629903314059-compute@developer.gserviceaccount.com'
assert_contains "${DEFAULT_CALLS}" $'--role\troles/secretmanager.secretAccessor'
assert_contains "${DEFAULT_CALLS}" $'--service-account\t629903314059-compute@developer.gserviceaccount.com'
assert_contains "${DEFAULT_CALLS}" $'--update-env-vars\tAUTOPLAY_GEMINI_MODEL=gemini-3.7-flash'
assert_contains "${DEFAULT_CALLS}" $'--update-secrets\tGEMINI_API_KEY=projects/629903314059/secrets/gemini-api-key:latest'
assert_not_contains "${DEFAULT_CALLS}" $'secrets\tversions\taccess'

PRODUCTION_CALLS="${TMP_DIR}/production.calls"
run_deploy "${PRODUCTION_CALLS}" \
  GEMINI_SECRET_PROJECT="527294552477" \
  >"${TMP_DIR}/production.out"
assert_contains "${PRODUCTION_CALLS}" $'--update-secrets\tGEMINI_API_KEY=projects/527294552477/secrets/gemini-api-key:latest'

TTL_WARNING_CALLS="${TMP_DIR}/ttl-warning.calls"
run_deploy "${TTL_WARNING_CALLS}" \
  CONFIGURE_FIRESTORE_TTL="true" \
  FAIL_TTL_UPDATE="true" \
  >"${TMP_DIR}/ttl-warning.out" 2>"${TMP_DIR}/ttl-warning.err"
assert_contains "${TMP_DIR}/ttl-warning.err" "TTL policy could not be configured; continuing deployment"
assert_contains "${TTL_WARNING_CALLS}" $'firestore\tfields\tttls\tupdate\texpireAt'
assert_contains "${TTL_WARNING_CALLS}" $'builds\tsubmit'
assert_contains "${TTL_WARNING_CALLS}" $'run\tdeploy'

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
assert_contains "${TMP_DIR}/missing.err" "could not verify projects/629903314059/secrets/gemini-api-key"
assert_not_contains "${MISSING_CALLS}" $'builds\tsubmit'
assert_not_contains "${MISSING_CALLS}" $'run\tdeploy'

MISSING_VERSION_CALLS="${TMP_DIR}/missing-version.calls"
if run_deploy "${MISSING_VERSION_CALLS}" FAIL_SECRET_VERSION="true" >"${TMP_DIR}/missing-version.out" 2>"${TMP_DIR}/missing-version.err"; then
  fail "deployment unexpectedly continued when the secret version could not be described"
fi
assert_contains "${TMP_DIR}/missing-version.err" "could not verify projects/629903314059/secrets/gemini-api-key:latest"
assert_not_contains "${MISSING_VERSION_CALLS}" $'secrets\tadd-iam-policy-binding'
assert_not_contains "${MISSING_VERSION_CALLS}" $'builds\tsubmit'
assert_not_contains "${MISSING_VERSION_CALLS}" $'run\tdeploy'

UNKNOWN_SECRET_PROJECT_CALLS="${TMP_DIR}/unknown-secret-project.calls"
if run_deploy "${UNKNOWN_SECRET_PROJECT_CALLS}" \
  GEMINI_SECRET_PROJECT="unavailable-project" \
  FAIL_PROJECT_DESCRIBE="unavailable-project" \
  >"${TMP_DIR}/unknown-secret-project.out" 2>"${TMP_DIR}/unknown-secret-project.err"; then
  fail "deployment unexpectedly continued when the secret project could not be resolved"
fi
assert_contains "${TMP_DIR}/unknown-secret-project.err" \
  "could not resolve project number for GEMINI_SECRET_PROJECT=unavailable-project"
assert_not_contains "${UNKNOWN_SECRET_PROJECT_CALLS}" $'builds\tsubmit'

UNKNOWN_RUNTIME_PROJECT_CALLS="${TMP_DIR}/unknown-runtime-project.calls"
if run_deploy "${UNKNOWN_RUNTIME_PROJECT_CALLS}" \
  GEMINI_SECRET_PROJECT="secret-project" \
  FAIL_SERVICE_DESCRIBE="true" \
  FAIL_PROJECT_DESCRIBE="andreyg-main" \
  >"${TMP_DIR}/unknown-runtime-project.out" 2>"${TMP_DIR}/unknown-runtime-project.err"; then
  fail "deployment unexpectedly continued when the runtime project could not be resolved"
fi
assert_contains "${TMP_DIR}/unknown-runtime-project.err" \
  "could not resolve project number for PROJECT_ID=andreyg-main"
assert_not_contains "${UNKNOWN_RUNTIME_PROJECT_CALLS}" $'builds\tsubmit'

echo "Secret Manager deployment contract tests passed."
