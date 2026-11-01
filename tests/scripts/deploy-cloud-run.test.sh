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

assert_line() {
  local file="$1"
  local expected="$2"
  grep -Fx -- "${expected}" "${file}" >/dev/null || fail "${file} did not contain the line: ${expected}"
}

# Each run starts with the optional service settings unset, whatever the caller's environment holds.
run_deploy() {
  local calls_file="$1"
  shift
  env \
    -u AUTOPLAY_PROVIDER \
    -u AUTOPLAY_ANTHROPIC_ORGANIZATION_ID \
    -u AUTOPLAY_ANTHROPIC_WORKSPACE_ID \
    -u AUTOPLAY_ANTHROPIC_SERVICE_ACCOUNT_ID \
    -u AUTOPLAY_ANTHROPIC_FEDERATION_RULE_ID \
    -u AUTOPLAY_ANTHROPIC_INFERENCE_GEO \
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
assert_contains "${DEFAULT_CALLS}" $'--update-env-vars\tAUTOPLAY_GEMINI_MODEL=gemini-3.8-flash'
assert_contains "${DEFAULT_CALLS}" $'--update-secrets\tGEMINI_API_KEY=projects/629903314059/secrets/gemini-api-key:latest'
assert_not_contains "${DEFAULT_CALLS}" $'secrets\tversions\taccess'
assert_contains "${DEFAULT_CALLS}" $'--max\t1'
assert_contains "${DEFAULT_CALLS}" $'--max-instances\t1'
assert_contains "${DEFAULT_CALLS}" $'builds\tsubmit\t--tag\tus-west1-docker.pkg.dev/andreyg-main/durak-game/durak-game:latest'
assert_contains "${DEFAULT_CALLS}" $'--allow-unauthenticated'
assert_contains "${DEFAULT_CALLS}" $'--min-instances\t0'
assert_contains "${DEFAULT_CALLS}" $'--concurrency\t200'
assert_contains "${DEFAULT_CALLS}" $'--timeout\t3600'
assert_contains "${DEFAULT_CALLS}" $'--cpu-boost'
assert_contains "${DEFAULT_CALLS}" 'LOGGING_STRUCTURED_FORMAT_CONSOLE=com.example.durakgame.logging.CloudLoggingJsonFormatter'

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
assert_contains "${CUSTOM_CALLS}" $'--update-env-vars\tAUTOPLAY_GEMINI_MODEL=gemini-3.7-flash,'
assert_contains "${CUSTOM_CALLS}" $'--member\tserviceAccount:durak-runtime@andreyg-main.iam.gserviceaccount.com'
assert_contains "${CUSTOM_CALLS}" $'--update-secrets\tGEMINI_API_KEY=projects/777777777777/secrets/alternate-key:7'
assert_not_contains "${CUSTOM_CALLS}" $'run\tservices\tdescribe'

TUNED_CALLS="${TMP_DIR}/tuned.calls"
run_deploy "${TUNED_CALLS}" \
  CONCURRENCY="500" \
  REQUEST_TIMEOUT="1800" \
  MIN_INSTANCES="1" \
  >"${TMP_DIR}/tuned.out"
assert_contains "${TUNED_CALLS}" $'--concurrency\t500'
assert_contains "${TUNED_CALLS}" $'--timeout\t1800'
assert_contains "${TUNED_CALLS}" $'--min-instances\t1'
assert_contains "${TUNED_CALLS}" $'--max-instances\t1'

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

# A prebuilt image (the deploy workflow's) is deployed as is: no Cloud Build run.
PREBUILT_CALLS="${TMP_DIR}/prebuilt.calls"
run_deploy "${PREBUILT_CALLS}" \
  IMAGE="us-west1-docker.pkg.dev/andreyg-main/durak-game/durak-game:abc123" \
  >"${TMP_DIR}/prebuilt.out"
assert_not_contains "${PREBUILT_CALLS}" $'builds\tsubmit'
assert_contains "${PREBUILT_CALLS}" $'--image\tus-west1-docker.pkg.dev/andreyg-main/durak-game/durak-game:abc123'
assert_contains "${PREBUILT_CALLS}" $'secrets\tadd-iam-policy-binding'

# Deploy-only (the workflow's mode) needs nothing beyond deploying the one service: no API enabling,
# no secret or IAM work, no TTL, no repository creation, no public-access policy, no project lookups,
# and the service keeps its secret mapping.
DEPLOY_ONLY_CALLS="${TMP_DIR}/deploy-only.calls"
run_deploy "${DEPLOY_ONLY_CALLS}" \
  DEPLOY_ONLY="true" \
  CONFIGURE_FIRESTORE_TTL="true" \
  IMAGE="us-west1-docker.pkg.dev/andreyg-main/durak-game/durak-game:abc123" \
  >"${TMP_DIR}/deploy-only.out"
assert_contains "${DEPLOY_ONLY_CALLS}" $'run\tdeploy\tdurak-game'
assert_contains "${DEPLOY_ONLY_CALLS}" $'--image\tus-west1-docker.pkg.dev/andreyg-main/durak-game/durak-game:abc123'
assert_contains "${DEPLOY_ONLY_CALLS}" $'--service-account\t629903314059-compute@developer.gserviceaccount.com'
assert_contains "${DEPLOY_ONLY_CALLS}" $'--max\t1'
assert_contains "${DEPLOY_ONLY_CALLS}" $'--max-instances\t1'
assert_contains "${DEPLOY_ONLY_CALLS}" $'--concurrency\t200'
assert_contains "${DEPLOY_ONLY_CALLS}" $'--timeout\t3600'
assert_contains "${DEPLOY_ONLY_CALLS}" 'LOGGING_STRUCTURED_FORMAT_CONSOLE=com.example.durakgame.logging.CloudLoggingJsonFormatter'
for unexpected in $'services\tenable' $'secrets\t' $'firestore\t' $'artifacts\t' $'builds\tsubmit' \
    $'projects\tdescribe' '--update-secrets' 'allow-unauthenticated'; do
  assert_not_contains "${DEPLOY_ONLY_CALLS}" "${unexpected}"
done
assert_contains "${TMP_DIR}/deploy-only.out" "Gemini secret: kept from the current service"

# Deploy-only only updates an existing service; it must not guess an identity for a new one.
DEPLOY_ONLY_MISSING_CALLS="${TMP_DIR}/deploy-only-missing.calls"
if run_deploy "${DEPLOY_ONLY_MISSING_CALLS}" \
  DEPLOY_ONLY="true" \
  FAIL_SERVICE_DESCRIBE="true" \
  IMAGE="us-west1-docker.pkg.dev/andreyg-main/durak-game/durak-game:abc123" \
  >"${TMP_DIR}/deploy-only-missing.out" 2>"${TMP_DIR}/deploy-only-missing.err"; then
  fail "deploy-only unexpectedly continued without an existing service"
fi
assert_contains "${TMP_DIR}/deploy-only-missing.err" "DEPLOY_ONLY updates an existing service"
assert_not_contains "${DEPLOY_ONLY_MISSING_CALLS}" $'run\tdeploy'

# Claude's settings reach the service only when given; deploy-only runs (the workflow's) keep them.
assert_not_contains "${DEFAULT_CALLS}" 'AUTOPLAY_PROVIDER'
assert_not_contains "${DEPLOY_ONLY_CALLS}" 'AUTOPLAY_ANTHROPIC_'
assert_contains "${DEFAULT_CALLS}" $'--update-env-vars\tAUTOPLAY_GEMINI_MODEL=gemini-3.8-flash,LOGGING_STRUCTURED_FORMAT_CONSOLE=com.example.durakgame.logging.CloudLoggingJsonFormatter\t'
CLAUDE_CALLS="${TMP_DIR}/claude.calls"
run_deploy "${CLAUDE_CALLS}" \
  DEPLOY_ONLY="true" \
  IMAGE="us-west1-docker.pkg.dev/andreyg-main/durak-game/durak-game:abc123" \
  AUTOPLAY_PROVIDER="anthropic" \
  AUTOPLAY_ANTHROPIC_ORGANIZATION_ID="6f1d0e2c-0000-4c6f-9a70-6d3e2f1a0b11" \
  AUTOPLAY_ANTHROPIC_WORKSPACE_ID="wrkspc_01Durak" \
  AUTOPLAY_ANTHROPIC_SERVICE_ACCOUNT_ID="svac_01Durak" \
  AUTOPLAY_ANTHROPIC_FEDERATION_RULE_ID="fdrl_01Durak" \
  AUTOPLAY_ANTHROPIC_INFERENCE_GEO="us" \
  >"${TMP_DIR}/claude.out"
assert_contains "${CLAUDE_CALLS}" $'--update-env-vars\tAUTOPLAY_GEMINI_MODEL=gemini-3.8-flash,LOGGING_STRUCTURED_FORMAT_CONSOLE=com.example.durakgame.logging.CloudLoggingJsonFormatter,AUTOPLAY_PROVIDER=anthropic,AUTOPLAY_ANTHROPIC_ORGANIZATION_ID=6f1d0e2c-0000-4c6f-9a70-6d3e2f1a0b11,AUTOPLAY_ANTHROPIC_WORKSPACE_ID=wrkspc_01Durak,AUTOPLAY_ANTHROPIC_SERVICE_ACCOUNT_ID=svac_01Durak,AUTOPLAY_ANTHROPIC_FEDERATION_RULE_ID=fdrl_01Durak,AUTOPLAY_ANTHROPIC_INFERENCE_GEO=us\t'
assert_contains "${TMP_DIR}/claude.out" "Bot provider: anthropic"

PARTIAL_CLAUDE_CALLS="${TMP_DIR}/partial-claude.calls"
run_deploy "${PARTIAL_CLAUDE_CALLS}" DEPLOY_ONLY="true" AUTOPLAY_PROVIDER="gemini" >"${TMP_DIR}/partial-claude.out"
assert_contains "${PARTIAL_CLAUDE_CALLS}" ',AUTOPLAY_PROVIDER=gemini'
assert_not_contains "${PARTIAL_CLAUDE_CALLS}" 'AUTOPLAY_ANTHROPIC_'

BAD_CLAUDE_CALLS="${TMP_DIR}/bad-claude.calls"
if run_deploy "${BAD_CLAUDE_CALLS}" AUTOPLAY_ANTHROPIC_FEDERATION_RULE_ID="fdrl_1,AUTOPLAY_PROVIDER=gemini" \
  >"${TMP_DIR}/bad-claude.out" 2>"${TMP_DIR}/bad-claude.err"; then
  fail "deployment unexpectedly continued with a value that would split --update-env-vars"
fi
assert_contains "${TMP_DIR}/bad-claude.err" "AUTOPLAY_ANTHROPIC_FEDERATION_RULE_ID may only contain"
if [[ -s "${BAD_CLAUDE_CALLS}" ]]; then
  fail "a rejected value still reached gcloud"
fi

# Unset keeps the service's value, the empty string sets it empty. service_env_after prints the
# service's variables after a recorded deploy: the "NAME=value" lines in $1, with every
# --update-env-vars list in $2 applied over them, as Cloud Run applies it.
service_env_after() {
  awk -F'\t' '
    function set(pair,    name) {
      name = substr(pair, 1, index(pair, "=") - 1)
      if (!(name in env)) order[++count] = name
      env[name] = substr(pair, index(pair, "=") + 1)
    }
    FNR == NR { set($0); next }
    $1 == "run" && $2 == "deploy" {
      for (i = 3; i < NF; i++) {
        if ($i == "--update-env-vars") {
          pairs = split($(i + 1), list, ",")
          for (j = 1; j <= pairs; j++) set(list[j])
        }
      }
    }
    END { for (i = 1; i <= count; i++) print order[i] "=" env[order[i]] }
  ' "$1" "$2"
}

EXISTING_ENV="${TMP_DIR}/existing.env"
cat >"${EXISTING_ENV}" <<'ENV'
AUTOPLAY_PROVIDER=anthropic
AUTOPLAY_ANTHROPIC_ORGANIZATION_ID=org-existing
AUTOPLAY_ANTHROPIC_WORKSPACE_ID=wrkspc_existing
AUTOPLAY_ANTHROPIC_SERVICE_ACCOUNT_ID=svac_existing
AUTOPLAY_ANTHROPIC_FEDERATION_RULE_ID=fdrl_existing
AUTOPLAY_ANTHROPIC_INFERENCE_GEO=us
ENV

# A release that names none of them (the workflow's) keeps every one.
service_env_after "${EXISTING_ENV}" "${DEPLOY_ONLY_CALLS}" >"${TMP_DIR}/kept.env"
while IFS= read -r line; do
  assert_line "${TMP_DIR}/kept.env" "${line}"
done <"${EXISTING_ENV}"

# An empty workspace (for a rule that names a single workspace) and an empty geo (no inference_geo
# field; removing the variable would bring back the application's default, global) are set empty.
CLEARED_CALLS="${TMP_DIR}/cleared.calls"
run_deploy "${CLEARED_CALLS}" \
  DEPLOY_ONLY="true" \
  IMAGE="us-west1-docker.pkg.dev/andreyg-main/durak-game/durak-game:abc123" \
  AUTOPLAY_ANTHROPIC_WORKSPACE_ID="" \
  AUTOPLAY_ANTHROPIC_INFERENCE_GEO="" \
  >"${TMP_DIR}/cleared.out"
assert_contains "${CLEARED_CALLS}" $',LOGGING_STRUCTURED_FORMAT_CONSOLE=com.example.durakgame.logging.CloudLoggingJsonFormatter,AUTOPLAY_ANTHROPIC_WORKSPACE_ID=,AUTOPLAY_ANTHROPIC_INFERENCE_GEO=\t'
service_env_after "${EXISTING_ENV}" "${CLEARED_CALLS}" >"${TMP_DIR}/cleared.env"
for line in "AUTOPLAY_ANTHROPIC_WORKSPACE_ID=" "AUTOPLAY_ANTHROPIC_INFERENCE_GEO=" \
    "AUTOPLAY_PROVIDER=anthropic" "AUTOPLAY_ANTHROPIC_ORGANIZATION_ID=org-existing" \
    "AUTOPLAY_ANTHROPIC_SERVICE_ACCOUNT_ID=svac_existing" "AUTOPLAY_ANTHROPIC_FEDERATION_RULE_ID=fdrl_existing"; do
  assert_line "${TMP_DIR}/cleared.env" "${line}"
done

# The other federation ids clear the same way, which leaves Claude unconfigured.
IDS_CLEARED_CALLS="${TMP_DIR}/ids-cleared.calls"
run_deploy "${IDS_CLEARED_CALLS}" \
  DEPLOY_ONLY="true" \
  AUTOPLAY_ANTHROPIC_ORGANIZATION_ID="" \
  AUTOPLAY_ANTHROPIC_SERVICE_ACCOUNT_ID="" \
  AUTOPLAY_ANTHROPIC_FEDERATION_RULE_ID="" \
  >"${TMP_DIR}/ids-cleared.out"
assert_contains "${IDS_CLEARED_CALLS}" $',AUTOPLAY_ANTHROPIC_ORGANIZATION_ID=,AUTOPLAY_ANTHROPIC_SERVICE_ACCOUNT_ID=,AUTOPLAY_ANTHROPIC_FEDERATION_RULE_ID=\t'
service_env_after "${EXISTING_ENV}" "${IDS_CLEARED_CALLS}" >"${TMP_DIR}/ids-cleared.env"
for line in "AUTOPLAY_ANTHROPIC_ORGANIZATION_ID=" "AUTOPLAY_ANTHROPIC_SERVICE_ACCOUNT_ID=" \
    "AUTOPLAY_ANTHROPIC_FEDERATION_RULE_ID=" "AUTOPLAY_ANTHROPIC_WORKSPACE_ID=wrkspc_existing" \
    "AUTOPLAY_ANTHROPIC_INFERENCE_GEO=us"; do
  assert_line "${TMP_DIR}/ids-cleared.env" "${line}"
done

# A provider is gemini or anthropic in any case, as the service reads it. Anything else, the empty
# string included, is refused before gcloud runs instead of leaving the old provider in place.
CASED_PROVIDER_CALLS="${TMP_DIR}/cased-provider.calls"
run_deploy "${CASED_PROVIDER_CALLS}" DEPLOY_ONLY="true" AUTOPLAY_PROVIDER="Anthropic" >"${TMP_DIR}/cased-provider.out"
assert_contains "${CASED_PROVIDER_CALLS}" $',AUTOPLAY_PROVIDER=Anthropic\t'
provider_case=0
for provider in "" "claude" " anthropic" "gemini,AUTOPLAY_ANTHROPIC_INFERENCE_GEO=us"; do
  provider_case=$((provider_case + 1))
  calls="${TMP_DIR}/bad-provider-${provider_case}.calls"
  if run_deploy "${calls}" DEPLOY_ONLY="true" AUTOPLAY_PROVIDER="${provider}" \
    >"${TMP_DIR}/bad-provider.out" 2>"${TMP_DIR}/bad-provider.err"; then
    fail "deployment unexpectedly continued with AUTOPLAY_PROVIDER='${provider}'"
  fi
  assert_contains "${TMP_DIR}/bad-provider.err" "AUTOPLAY_PROVIDER must be gemini or anthropic"
  if [[ -s "${calls}" ]]; then
    fail "AUTOPLAY_PROVIDER='${provider}' still reached gcloud"
  fi
done

# Nothing given can add or split assignments in the --update-env-vars list.
injection_case=0
for name in AUTOPLAY_ANTHROPIC_WORKSPACE_ID AUTOPLAY_ANTHROPIC_INFERENCE_GEO; do
  for value in "x,AUTOPLAY_PROVIDER=gemini" "us=global" "wrk space" $'wrk\nAUTOPLAY_PROVIDER=gemini'; do
    injection_case=$((injection_case + 1))
    calls="${TMP_DIR}/injection-${injection_case}.calls"
    if run_deploy "${calls}" DEPLOY_ONLY="true" "${name}=${value}" \
      >"${TMP_DIR}/injection.out" 2>"${TMP_DIR}/injection.err"; then
      fail "deployment unexpectedly continued with ${name}='${value}'"
    fi
    assert_contains "${TMP_DIR}/injection.err" "${name} may only contain"
    if [[ -s "${calls}" ]]; then
      fail "${name}='${value}' still reached gcloud"
    fi
  done
done

echo "Secret Manager deployment contract tests passed."
