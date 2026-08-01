#!/usr/bin/env bash
#
# Runs every checker self-test in scripts/test-check-*.sh and fails if ANY of them fails.
#
# The mirror gates (db.version overlays, Jetty pin parity, version literals, CodeQL
# baseline drift and expiry) are hand-written checkers that no unit test exercises. Each
# has a sibling fixture test proving it still fires on a planted defect — but until #220
# nothing ran those fixture tests, so a checker could rot into a permanent green and the
# fixture test that would have caught it sat unexecuted in the tree.
#
# The suite is discovered by GLOB, not by a hand-maintained list, so a self-test added
# later joins this gate by existing. The empty-glob case is an ERROR, not a pass: if the
# scripts are renamed or moved, a silently empty loop is exactly the false-green this
# gate exists to prevent.
#
# Every test is run even after one fails (the full picture beats the first casualty), and
# the aggregated status is returned at the end. `set -e` alone would NOT do this: an
# early failure would abort the loop, and a naive loop without aggregation would return
# only the LAST test's status, so a red first entry followed by a green last entry would
# report success.
#
# Requirements: bash, sed, mktemp, node, python3 — no network, no Docker, seconds-fast.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

shopt -s nullglob
tests=("${repo_root}"/scripts/test-check-*.sh)
shopt -u nullglob

if [ ${#tests[@]} -eq 0 ]; then
  echo "FAIL: no scripts/test-check-*.sh found — the self-test glob matched nothing." >&2
  echo "      Either the checker self-tests were deleted, or they were renamed and this" >&2
  echo "      gate now proves nothing. Fix the glob rather than deleting this step." >&2
  exit 1
fi

status=0
passed=0
failed=0

for test_script in "${tests[@]}"; do
  name="$(basename "${test_script}")"
  echo "::group::${name}"
  if bash "${test_script}"; then
    echo "PASS ${name}"
    passed=$((passed + 1))
  else
    rc=$?
    echo "FAIL ${name} (exit ${rc})" >&2
    failed=$((failed + 1))
    status=1
  fi
  echo "::endgroup::"
done

echo "checker self-tests: ${passed} passed, ${failed} failed, ${#tests[@]} total."
exit "${status}"
