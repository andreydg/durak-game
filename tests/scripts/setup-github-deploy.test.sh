#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SETUP_SCRIPT="${ROOT}/scripts/setup-github-deploy.sh"
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
  projects:describe:andreyg-main)
    echo "629903314059"
    ;;
  run:services:describe)
    [[ "${FAIL_SERVICE_DESCRIBE:-false}" != "true" ]] || exit 1
    echo "durak-runtime@andreyg-main.iam.gserviceaccount.com"
    ;;
  iam:service-accounts:describe | iam:workload-identity-pools:describe)
    [[ "${EXISTING:-false}" == "true" ]] || exit 1
    ;;
  iam:workload-identity-pools:providers)
    if [[ "${4:-}" == "describe" ]]; then
      [[ "${EXISTING:-false}" == "true" ]] || exit 1
    fi
    ;;
  run:services:add-iam-policy-binding)
    # Fail the first FAIL_BINDING_ATTEMPTS calls, like IAM before a new account has propagated.
    count_file="${CALLS_FILE}.bindings"
    count=$(( $(cat "${count_file}" 2>/dev/null || echo 0) + 1 ))
    echo "${count}" >"${count_file}"
    [[ "${count}" -gt "${FAIL_BINDING_ATTEMPTS:-0}" ]] || exit 1
    ;;
esac
FAKE
chmod +x "${FAKE_GCLOUD}"

FAKE_GH="${TMP_DIR}/gh"
cat >"${FAKE_GH}" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail

(
  printf '%s' "$1"
  shift
  printf '\t%s' "$@"
  printf '\n'
) >>"${CALLS_FILE}"

if [[ "${1:-}" == "api" ]]; then
  echo "1215352511"
fi
FAKE
chmod +x "${FAKE_GH}"

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
    GH_BIN="${FAKE_GH}" \
    PROJECT_ID="andreyg-main" \
    REGION="us-west1" \
    GITHUB_REPOSITORY="andreydg/durak-game" \
    RETRY_DELAY_SECONDS="0" \
    "$@" \
    bash "${SETUP_SCRIPT}"
}

DEPLOYER=$'--member\tserviceAccount:durak-deployer@andreyg-main.iam.gserviceaccount.com'
POOL="projects/629903314059/locations/global/workloadIdentityPools/github"

NEW_CALLS="${TMP_DIR}/new.calls"
run_setup "${NEW_CALLS}" PUBLIC_URL="https://durak.andreyg.com/" >"${TMP_DIR}/new.out"
assert_contains "${NEW_CALLS}" $'iam\tservice-accounts\tcreate\tdurak-deployer'
# Each grant is scoped to the one resource a release touches.
assert_contains "${NEW_CALLS}" $'run\tservices\tadd-iam-policy-binding\tdurak-game\t--region\tus-west1\t'"${DEPLOYER}"$'\t--role\troles/run.developer'
assert_contains "${NEW_CALLS}" $'projects\tadd-iam-policy-binding\tandreyg-main\t'"${DEPLOYER}"$'\t--role\troles/run.viewer\t--condition=None'
assert_contains "${NEW_CALLS}" $'artifacts\trepositories\tadd-iam-policy-binding\tdurak-game\t--location\tus-west1\t'"${DEPLOYER}"$'\t--role\troles/artifactregistry.writer'
assert_contains "${NEW_CALLS}" $'iam\tservice-accounts\tadd-iam-policy-binding\tdurak-runtime@andreyg-main.iam.gserviceaccount.com\t'"${DEPLOYER}"$'\t--role\troles/iam.serviceAccountUser'
assert_not_contains "${NEW_CALLS}" 'roles/editor'
assert_not_contains "${NEW_CALLS}" 'roles/run.admin'
assert_not_contains "${NEW_CALLS}" 'keys'
# Only this repository's (numeric id) deploy workflow on main can get a token.
assert_contains "${NEW_CALLS}" $'iam\tworkload-identity-pools\tcreate\tgithub'
assert_contains "${NEW_CALLS}" $'providers\tcreate-oidc\tdurak-game\t--issuer-uri\thttps://token.actions.githubusercontent.com'
assert_contains "${NEW_CALLS}" "assertion.repository_id == '1215352511' && assertion.ref == 'refs/heads/main' && assertion.workflow_ref.startsWith('andreydg/durak-game/.github/workflows/deploy.yml@')"
assert_contains "${NEW_CALLS}" 'attribute.repository_id=assertion.repository_id'
assert_contains "${NEW_CALLS}" $'--member\tprincipalSet://iam.googleapis.com/'"${POOL}"$'/attribute.repository_id/1215352511\t--role\troles/iam.workloadIdentityUser'
assert_contains "${NEW_CALLS}" $'variable\tset\tGCP_PROJECT_ID\t--body\tandreyg-main\t--repo\tandreydg/durak-game'
assert_contains "${NEW_CALLS}" $'variable\tset\tGCP_REGION\t--body\tus-west1'
assert_contains "${NEW_CALLS}" $'variable\tset\tGCP_WORKLOAD_IDENTITY_PROVIDER\t--body\t'"${POOL}"$'/providers/durak-game'
assert_contains "${NEW_CALLS}" $'variable\tset\tGCP_DEPLOY_SERVICE_ACCOUNT\t--body\tdurak-deployer@andreyg-main.iam.gserviceaccount.com'
assert_contains "${NEW_CALLS}" $'variable\tset\tPUBLIC_URL\t--body\thttps://durak.andreyg.com\t'

# Re-running updates the provider's condition instead of failing on existing resources.
EXISTING_CALLS="${TMP_DIR}/existing.calls"
run_setup "${EXISTING_CALLS}" EXISTING="true" >"${TMP_DIR}/existing.out"
assert_not_contains "${EXISTING_CALLS}" $'service-accounts\tcreate'
assert_not_contains "${EXISTING_CALLS}" $'workload-identity-pools\tcreate'
assert_not_contains "${EXISTING_CALLS}" 'create-oidc'
assert_contains "${EXISTING_CALLS}" $'providers\tupdate-oidc\tdurak-game'
assert_contains "${EXISTING_CALLS}" "assertion.ref == 'refs/heads/main'"
assert_not_contains "${EXISTING_CALLS}" $'variable\tset\tPUBLIC_URL'

# An explicit runtime identity wins over the service's current one.
EXPLICIT_CALLS="${TMP_DIR}/explicit.calls"
run_setup "${EXPLICIT_CALLS}" RUNTIME_SERVICE_ACCOUNT="other@andreyg-main.iam.gserviceaccount.com" >"${TMP_DIR}/explicit.out"
assert_contains "${EXPLICIT_CALLS}" $'service-accounts\tadd-iam-policy-binding\tother@andreyg-main.iam.gserviceaccount.com'
assert_not_contains "${EXPLICIT_CALLS}" $'run\tservices\tdescribe'

RETRY_CALLS="${TMP_DIR}/retry.calls"
run_setup "${RETRY_CALLS}" FAIL_BINDING_ATTEMPTS="2" >"${TMP_DIR}/retry.out" 2>"${TMP_DIR}/retry.err"
[[ "$(grep -c $'^run\tservices\tadd-iam-policy-binding' "${RETRY_CALLS}")" == "3" ]] \
  || fail "expected the service binding to succeed on the third attempt"

NO_SERVICE_CALLS="${TMP_DIR}/no-service.calls"
if run_setup "${NO_SERVICE_CALLS}" FAIL_SERVICE_DESCRIBE="true" >"${TMP_DIR}/no-service.out" 2>"${TMP_DIR}/no-service.err"; then
  fail "setup unexpectedly continued without a runtime identity"
fi
assert_contains "${TMP_DIR}/no-service.err" "could not read durak-game's runtime identity"
assert_not_contains "${NO_SERVICE_CALLS}" 'add-iam-policy-binding'

echo "GitHub deploy setup contract tests passed."
