#!/usr/bin/env bash
#
# Run the "mirror" gates that the CI build job enforces but a local test run never touches.
#
# Each of these compares a hand-maintained mirror against its source of truth:
#
#   openapi.json          vs the JAX-RS resource annotations
#   the locale JSONs      vs en.json's key set
#   db.version (3 files)  vs the newest dbupdate-NNN migration
#   poms + package.json   vs the release tag            (only with a tag argument)
#
# Drift in a mirror cannot fail a unit test, so without this it surfaces only in the push
# build. For a tag push that means a failed release build on an already-public tag, which
# is exactly how v3.6.7 lost its first tag: three audit-log query params and one dark-mode
# form param were added to the resources but never mirrored into openapi.json.
#
# Every gate runs bare so its own exit code is the verdict -- a piped gate would report the
# filter's status instead.
#
# Usage: scripts/check-release-mirrors.sh [vX.Y.Z]
#   Without a tag argument the version-consistency gate is skipped (there is nothing to
#   compare against); all other gates still run.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

TAG="${1:-}"
failed=0

run_gate() {
  local name="$1"
  shift
  printf '\n--- %s ---\n' "$name"
  "$@"
  local rc=$?
  if [ "$rc" -ne 0 ]; then
    printf '*** FAILED (exit %d): %s\n' "$rc" "$name"
    failed=1
  fi
}

if ! command -v node >/dev/null 2>&1; then
  printf 'node not found on PATH; the parity gates cannot run.\n' >&2
  exit 1
fi

run_gate "OpenAPI spec parity (openapi.json vs JAX-RS resources)" \
  node scripts/check-openapi-parity.mjs

run_gate "i18n key parity (locale JSONs vs en.json)" \
  bash -c 'cd docs-web/src/main/webapp && node scripts/check-i18n-parity.mjs'

run_gate "db.version parity (3 overlays vs newest migration)" \
  bash scripts/check-db-version.sh

if [ -n "$TAG" ]; then
  run_gate "version consistency (tag vs poms vs package.json)" \
    bash scripts/check-version-consistency.sh "$TAG"
else
  printf '\n--- version consistency: skipped (no tag argument) ---\n'
fi

printf '\n'
if [ "$failed" -ne 0 ]; then
  cat >&2 <<'MSG'
Release mirror gates FAILED.
The same checks run in the CI build job, so pushing this state fails the build.
MSG
  exit 1
fi

printf 'All release mirror gates passed.\n'
