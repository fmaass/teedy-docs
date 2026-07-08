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

fail_fixture="$tmp_dir/fail"
pass_fixture="$tmp_dir/pass"
write_fixture "$fail_fixture" 40 39 40
write_fixture "$pass_fixture" 40 40 41

fail_output="$tmp_dir/fail.out"
pass_output="$tmp_dir/pass.out"

if bash "$checker" "$fail_fixture" >"$fail_output" 2>&1; then
  echo "FAIL: checker passed when docs-web dev db.version was behind" >&2
  cat "$fail_output" >&2
  exit 1
fi

echo "FAIL fixture correctly rejected:"
cat "$fail_output"

if ! bash "$checker" "$pass_fixture" >"$pass_output" 2>&1; then
  echo "FAIL: checker rejected fixture with all db.version values >= latest migration" >&2
  cat "$pass_output" >&2
  exit 1
fi

echo "PASS fixture correctly accepted:"
cat "$pass_output"
