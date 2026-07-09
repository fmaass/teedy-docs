#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
checker="$script_dir/check-db-version.sh"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/check-db-version.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

write_fixture() {
  local root="$1"
  local core_version="$2"
  local dev_version="$3"
  local prod_version="$4"

  mkdir -p "$root/docs-core/src/main/resources/db/update"
  mkdir -p "$root/docs-core/src/main/resources"
  mkdir -p "$root/docs-web/src/dev/resources"
  mkdir -p "$root/docs-web/src/prod/resources"

  : > "$root/docs-core/src/main/resources/db/update/dbupdate-039-0.sql"
  : > "$root/docs-core/src/main/resources/db/update/dbupdate-040-0.sql"

  printf 'db.version=%s\n' "$core_version" > "$root/docs-core/src/main/resources/config.properties"
  printf 'db.version=%s\n' "$dev_version" > "$root/docs-web/src/dev/resources/config.properties"
  printf 'db.version=%s\n' "$prod_version" > "$root/docs-web/src/prod/resources/config.properties"
}

# Latest migration in every fixture is dbupdate-040-0.sql, so the exact-match target is 40.
behind_fixture="$tmp_dir/behind"
premature_fixture="$tmp_dir/premature"
pass_fixture="$tmp_dir/pass"
# BEHIND: dev version 39 < 40 latest migration -> must fail.
write_fixture "$behind_fixture" 40 39 40
# PREMATURE bump: prod version 41 > 40 latest migration -> must fail (the BL-032 bug).
write_fixture "$premature_fixture" 40 40 41
# EXACT: every config == 40 latest migration -> must pass.
write_fixture "$pass_fixture" 40 40 40

behind_output="$tmp_dir/behind.out"
premature_output="$tmp_dir/premature.out"
pass_output="$tmp_dir/pass.out"

if bash "$checker" "$behind_fixture" >"$behind_output" 2>&1; then
  echo "FAIL: checker passed when docs-web dev db.version was BEHIND the latest migration" >&2
  cat "$behind_output" >&2
  exit 1
fi

echo "BEHIND fixture correctly rejected:"
cat "$behind_output"

if bash "$checker" "$premature_fixture" >"$premature_output" 2>&1; then
  echo "FAIL: checker passed on a PREMATURE db.version bump (41 > latest migration 40)" >&2
  cat "$premature_output" >&2
  exit 1
fi

echo "PREMATURE-bump fixture correctly rejected:"
cat "$premature_output"

if ! bash "$checker" "$pass_fixture" >"$pass_output" 2>&1; then
  echo "FAIL: checker rejected fixture with all db.version values == latest migration" >&2
  cat "$pass_output" >&2
  exit 1
fi

echo "PASS fixture correctly accepted:"
cat "$pass_output"
