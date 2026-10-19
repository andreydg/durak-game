#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHECK_SCRIPT="${ROOT}/scripts/check-release-handover.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

HEAD_SHA="4d4371d58ffd7f47c8c4577db7cdb057213fe953"
TRIGGER="rmgpgab-durak-game-us-west1-andreydg-durak-game--maubo"
GITHUB_IMAGE="us-west1-docker.pkg.dev/andreyg-main/durak-game/durak-game:${HEAD_SHA}"
# The trigger tags with the commit too; only the repository path tells the two deployers apart.
LEGACY_IMAGE="us-west1-docker.pkg.dev/andreyg-main/cloud-run-source-deploy/durak-game/durak-game:${HEAD_SHA}"

# Answers like gcloud for the trigger ($DISABLED), its ongoing builds ($ONGOING, one id per line),
# and the service ($IMAGE, $READY_REVISION, $LATEST_PERCENT); records every call.
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
case "$1:$2:$3" in
  builds:triggers:describe)
    printf '{"id":"5fb0f632-8345-4987-a52b-44bb0e54a75c","name":"%s","disabled":%s}' "$4" "${DISABLED}"
    ;;
  builds:list:--ongoing)
    [[ "$*" == *"--filter=buildTriggerId=5fb0f632-8345-4987-a52b-44bb0e54a75c"* ]] || exit 3
    if [[ -z "${ONGOING}" ]]; then
      echo "WARNING: The following filter keys were not present in any resource : buildTriggerId" >&2
    else
      printf '%s\n' "${ONGOING}"
    fi
    ;;
  run:services:describe)
    printf '{"spec":{"template":{"spec":{"containers":[{"image":"%s"}]}}},' "${IMAGE}"
    printf '"status":{"latestCreatedRevisionName":"durak-game-00043","latestReadyRevisionName":"%s",' "${READY_REVISION}"
    printf '"traffic":[{"latestRevision":true,"percent":%s}]}}' "${LATEST_PERCENT}"
    ;;
  *)
    echo "unexpected gcloud call: $*" >&2
    exit 1
    ;;
esac
FAKE
chmod +x "${FAKE_GCLOUD}"

FAKE_GH="${TMP_DIR}/gh"
cat >"${FAKE_GH}" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf 'gh\t%s\n' "$*" >>"${CALLS_FILE}"
[[ "$2" == "repos/andreydg/durak-game/git/ref/heads/main" ]] || exit 1
echo "${HEAD}"
FAKE
chmod +x "${FAKE_GH}"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

run_check() {
  local name="$1"
  shift
  env \
    CALLS_FILE="${TMP_DIR}/${name}.calls" \
    GCLOUD_BIN="${FAKE_GCLOUD}" \
    GH_BIN="${FAKE_GH}" \
    PROJECT_ID="andreyg-main" \
    REGION="us-west1" \
    GITHUB_REPOSITORY="andreydg/durak-game" \
    LEGACY_TRIGGER="${TRIGGER}" \
    HEAD="${HEAD_SHA}" \
    DISABLED="true" \
    ONGOING="" \
    IMAGE="${GITHUB_IMAGE}" \
    READY_REVISION="durak-game-00043" \
    LATEST_PERCENT="100" \
    "$@" \
    bash "${CHECK_SCRIPT}" >"${TMP_DIR}/${name}.out" 2>"${TMP_DIR}/${name}.err"
}

expect_incomplete() {
  local name="$1" message="$2"
  shift 2
  if run_check "${name}" "$@"; then
    fail "${name}: the handover was reported complete"
  fi
  grep -F -- "${message}" "${TMP_DIR}/${name}.out" >/dev/null \
    || fail "${name}: expected '${message}' in: $(cat "${TMP_DIR}/${name}.out")"
  grep -F "keep merges to main frozen" "${TMP_DIR}/${name}.out" >/dev/null || fail "${name}: missing freeze reminder"
}

# Done: trigger disabled, nothing in flight, and GitHub's image for the head serves all traffic.
run_check done || fail "a completed handover was reported incomplete: $(cat "${TMP_DIR}/done.out")"
grep -F "Merges can resume" "${TMP_DIR}/done.out" >/dev/null || fail "missing the all-clear"

# Disabling the trigger is not enough: builds it already started still deploy at their end.
expect_incomplete enabled "is enabled: disable it" DISABLED="null"
expect_incomplete in-flight "still queued or running" ONGOING=$'b-111\nb-222'
grep -F "gcloud builds cancel b-111 --region global --project andreyg-main" "${TMP_DIR}/in-flight.out" >/dev/null \
  || fail "missing the cancel command for each build"
grep -F "gcloud builds cancel b-222" "${TMP_DIR}/in-flight.out" >/dev/null || fail "missing the second build"

# A trigger build that finished after GitHub's release left its own image, for the same commit.
expect_incomplete legacy-last "runs ${LEGACY_IMAGE}" IMAGE="${LEGACY_IMAGE}"
grep -F "run the Deploy workflow" "${TMP_DIR}/legacy-last.out" >/dev/null || fail "missing the re-run advice"
expect_incomplete older-commit "not ${GITHUB_IMAGE}" IMAGE="us-west1-docker.pkg.dev/andreyg-main/durak-game/durak-game:c2fb23fe6e3b939427cb72794316f761d1f64403"
expect_incomplete not-ready "latest revision ready: false" READY_REVISION="durak-game-00042"
expect_incomplete pinned "its traffic: 0%" LATEST_PERCENT="0"

# Read-only: describe, list and GET calls only.
for name in done enabled in-flight legacy-last; do
  if grep -v -E $'^builds\ttriggers\tdescribe\t|^builds\tlist\t--ongoing\t|^run\tservices\tdescribe\t|^gh\tapi repos/' \
      "${TMP_DIR}/${name}.calls" >/dev/null; then
    fail "${name}: unexpected call: $(grep -v -E $'^builds\ttriggers\tdescribe|^builds\tlist|^run\tservices\tdescribe|^gh' "${TMP_DIR}/${name}.calls")"
  fi
done

echo "Release handover check contract tests passed."
