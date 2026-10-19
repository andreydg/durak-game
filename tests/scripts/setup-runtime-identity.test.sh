#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SETUP_SCRIPT="${ROOT}/scripts/setup-runtime-identity.sh"
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
  iam:service-accounts:describe)
    [[ "${SA_EXISTS:-false}" == "true" ]] || exit 1
    ;;
  projects:add-iam-policy-binding:*)
    # Fail the first FAIL_BINDING_ATTEMPTS calls, like IAM before a new account has propagated.
    count_file="${CALLS_FILE}.bindings"
    count=$(( $(cat "${count_file}" 2>/dev/null || echo 0) + 1 ))
    echo "${count}" >"${count_file}"
    [[ "${count}" -gt "${FAIL_BINDING_ATTEMPTS:-0}" ]] || exit 1
    ;;
  secrets:add-iam-policy-binding:*)
    [[ "${FAIL_SECRET_BINDING:-false}" != "true" ]] || exit 1
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

run_setup() {
  local calls_file="$1"
  shift
  env \
    CALLS_FILE="${calls_file}" \
    GCLOUD_BIN="${FAKE_GCLOUD}" \
    PROJECT_ID="andreyg-main" \
    RETRY_DELAY_SECONDS="0" \
    "$@" \
    bash "${SETUP_SCRIPT}"
}

RUNTIME_MEMBER=$'--member\tserviceAccount:durak-runtime@andreyg-main.iam.gserviceaccount.com'

NEW_CALLS="${TMP_DIR}/new.calls"
run_setup "${NEW_CALLS}" FIRESTORE_DATABASE_ID="durak-store" >"${TMP_DIR}/new.out"
assert_contains "${NEW_CALLS}" $'iam\tservice-accounts\tcreate\tdurak-runtime'
assert_contains "${NEW_CALLS}" $'projects\tadd-iam-policy-binding\tandreyg-main'
assert_contains "${NEW_CALLS}" "${RUNTIME_MEMBER}"
assert_contains "${NEW_CALLS}" $'--role\troles/datastore.user'
assert_contains "${NEW_CALLS}" $'--condition\texpression=resource.name=="projects/andreyg-main/databases/durak-store",title=durak-game database only'
assert_contains "${NEW_CALLS}" $'secrets\tadd-iam-policy-binding\tgemini-api-key\t--project\tandreyg-main'
assert_contains "${NEW_CALLS}" $'--role\troles/secretmanager.secretAccessor'
# Least privilege: no broad project roles, and the running service is left alone.
assert_not_contains "${NEW_CALLS}" 'roles/editor'
assert_not_contains "${NEW_CALLS}" 'roles/run.'
assert_not_contains "${NEW_CALLS}" $'run\tdeploy'
assert_not_contains "${NEW_CALLS}" $'run\tservices\tupdate'

EXISTING_CALLS="${TMP_DIR}/existing.calls"
run_setup "${EXISTING_CALLS}" SA_EXISTS="true" >"${TMP_DIR}/existing.out"
assert_not_contains "${EXISTING_CALLS}" $'iam\tservice-accounts\tcreate'
assert_contains "${EXISTING_CALLS}" $'--condition\texpression=resource.name=="projects/andreyg-main/databases/(default)"'
assert_contains "${EXISTING_CALLS}" $'secrets\tadd-iam-policy-binding\tgemini-api-key'

CUSTOM_CALLS="${TMP_DIR}/custom.calls"
run_setup "${CUSTOM_CALLS}" \
  RUNTIME_SA_NAME="durak-prod" \
  GEMINI_SECRET="alternate-key" \
  GEMINI_SECRET_PROJECT="secret-project" \
  >"${TMP_DIR}/custom.out"
assert_contains "${CUSTOM_CALLS}" $'--member\tserviceAccount:durak-prod@andreyg-main.iam.gserviceaccount.com'
assert_contains "${CUSTOM_CALLS}" $'secrets\tadd-iam-policy-binding\talternate-key\t--project\tsecret-project'

# IAM may not see a brand-new account yet: the binding is retried until it sticks.
RETRY_CALLS="${TMP_DIR}/retry.calls"
run_setup "${RETRY_CALLS}" FAIL_BINDING_ATTEMPTS="2" >"${TMP_DIR}/retry.out" 2>"${TMP_DIR}/retry.err"
[[ "$(grep -c $'^projects\tadd-iam-policy-binding' "${RETRY_CALLS}")" == "3" ]] \
  || fail "expected the Firestore binding to succeed on the third attempt"
assert_contains "${RETRY_CALLS}" $'secrets\tadd-iam-policy-binding'

FAILED_CALLS="${TMP_DIR}/failed.calls"
if run_setup "${FAILED_CALLS}" FAIL_SECRET_BINDING="true" >"${TMP_DIR}/failed.out" 2>"${TMP_DIR}/failed.err"; then
  fail "setup unexpectedly succeeded when the secret binding kept failing"
fi
assert_not_contains "${TMP_DIR}/failed.out" "Done."

echo "Runtime identity setup contract tests passed."
