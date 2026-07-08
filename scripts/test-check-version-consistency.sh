#!/usr/bin/env bash
# Synthetic-fixture tests for check-version-consistency.sh. Builds throwaway
# repo roots with known pom.xml / package.json versions and asserts the checker
# passes when they agree with the tag and fails when any of the three disagree.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
checker="$script_dir/check-version-consistency.sh"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/check-version.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

write_fixture() {
  local root="$1"
  local pom_version="$2"
  local package_version="$3"

  mkdir -p "$root/docs-web/src/main/webapp"
  cat > "$root/pom.xml" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.sismics.docs</groupId>
  <artifactId>docs-parent</artifactId>
  <packaging>pom</packaging>
  <version>$pom_version</version>
</project>
POM
  cat > "$root/docs-web/src/main/webapp/package.json" <<PKG
{
  "name": "teedy-docs",
  "version": "$package_version"
}
PKG
}

pass=0
fail=0

expect() {
  local desc="$1" expected_rc="$2" root="$3" tag="$4"
  set +e
  bash "$checker" "$tag" "$root" >/dev/null 2>&1
  local rc=$?
  set -e
  if [[ "$rc" -eq "$expected_rc" ]]; then
    echo "ok   - $desc"
    pass=$((pass + 1))
  else
    echo "FAIL - $desc (expected rc=$expected_rc, got rc=$rc)"
    fail=$((fail + 1))
  fi
}

# All three agree (tag has leading v) -> pass.
root="$tmp_dir/agree"
write_fixture "$root" "3.0.0" "3.0.0"
expect "tag==pom==package all 3.0.0 passes" 0 "$root" "v3.0.0"

# pom disagrees with package -> fail.
root="$tmp_dir/pom-mismatch"
write_fixture "$root" "3.0.1" "3.0.0"
expect "pom != package fails" 1 "$root" "v3.0.0"

# tag disagrees with pom/package -> fail.
root="$tmp_dir/tag-mismatch"
write_fixture "$root" "3.0.0" "3.0.0"
expect "tag != pom/package fails" 1 "$root" "v2.9.9"

# No tag, pom==package -> pass (tag check skipped).
root="$tmp_dir/no-tag"
write_fixture "$root" "3.0.0" "3.0.0"
expect "no tag, pom==package passes" 0 "$root" ""

echo
echo "Passed: $pass, Failed: $fail"
[[ "$fail" -eq 0 ]]
