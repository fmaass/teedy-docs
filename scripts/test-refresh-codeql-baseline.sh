#!/usr/bin/env bash
# Synthetic-fixture tests for refresh-codeql-baseline.mjs. Builds a throwaway git
# repo (an isolated fixture, NOT the project repo) with a known sink file committed
# at an "old-rev", then mutates the working tree to reproduce each drift class and
# asserts the tool remaps a byte-identical shift and REFUSES anything else.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tool="$script_dir/refresh-codeql-baseline.mjs"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/refresh-codeql.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

repo="$tmp_dir/repo"
mkdir -p "$repo/src"

# Canonical committed sink file. The sink is the Files.newInputStream line (line 5,
# startColumn 5). The baseline id below points at src/Sink.java:5:5.
write_canonical_sink() {
  cat > "$repo/src/Sink.java" <<'JAVA'
package x;

class Sink {
  void go(java.nio.file.Path sinkPath) throws Exception {
    Files.newInputStream(sinkPath);
  }
}
JAVA
}

# A pristine one-entry baseline pointing at the canonical sink coordinate.
write_baseline() {
  cat > "$repo/baseline.json" <<'JSON'
{
  "schema": {
    "required": ["id", "finding", "reason", "owner", "introduced", "expires", "compensating_control", "removal_issue"]
  },
  "findings": [
    {
      "id": "java/path-injection@src/Sink.java:5:5",
      "finding": "fixture sink",
      "reason": "Triaged already-mitigated (fixture).",
      "owner": "tester",
      "introduced": "2026-07-18",
      "expires": "2026-10-18",
      "compensating_control": "n/a fixture",
      "removal_issue": "#0"
    }
  ]
}
JSON
}

write_canonical_sink
write_baseline
git -C "$repo" init -q
git -C "$repo" add src/Sink.java baseline.json
git -C "$repo" -c user.email=t@example.invalid -c user.name=tester commit -q -m "old-rev"
OLD_REV="$(git -C "$repo" rev-parse HEAD)"

pass=0
fail=0

run_tool() {
  set +e
  node "$tool" "$OLD_REV" --baseline "$repo/baseline.json" --root "$repo" >"$tmp_dir/out.log" 2>&1
  local rc=$?
  set -e
  echo "$rc"
}

expect_rc() {
  local desc="$1" expected="$2" actual="$3"
  if [[ "$actual" -eq "$expected" ]]; then
    echo "ok   - $desc (rc=$actual)"
    pass=$((pass + 1))
  else
    echo "FAIL - $desc (expected rc=$expected, got rc=$actual)"
    sed 's/^/       /' "$tmp_dir/out.log"
    fail=$((fail + 1))
  fi
}

assert_id() {
  local desc="$1" expected_id="$2"
  if grep -qF "\"$expected_id\"" "$repo/baseline.json"; then
    echo "ok   - $desc"
    pass=$((pass + 1))
  else
    echo "FAIL - $desc (id \"$expected_id\" not in baseline)"
    fail=$((fail + 1))
  fi
}

# (a) DRIFT: insert 3 blank lines above the sink -> sink shifts 5 -> 8, content
#     byte-identical -> remap, exit 0.
write_canonical_sink
write_baseline
{ printf '\n\n\n'; cat "$repo/src/Sink.java"; } > "$repo/src/Sink.java.tmp"
mv "$repo/src/Sink.java.tmp" "$repo/src/Sink.java"
rc="$(run_tool)"
expect_rc "byte-identical drift remaps (exit 0)" 0 "$rc"
assert_id "drift remapped id to line 8" "java/path-injection@src/Sink.java:8:5"

# (b) CHANGED CONTENT: edit the sink line's bytes in place -> old bytes absent ->
#     REFUSE (nonzero), baseline untouched.
write_canonical_sink
write_baseline
sed -i 's/Files.newInputStream(sinkPath);/Files.newInputStream(otherPath);/' "$repo/src/Sink.java"
rc="$(run_tool)"
expect_rc "changed sink content refuses (nonzero)" 1 "$rc"
assert_id "changed-content baseline left untouched at 5:5" "java/path-injection@src/Sink.java:5:5"

# (c-zero) 0 MATCHES: delete the sink line entirely -> REFUSE (nonzero).
write_canonical_sink
write_baseline
sed -i '/Files.newInputStream(sinkPath);/d' "$repo/src/Sink.java"
rc="$(run_tool)"
expect_rc "deleted sink line refuses (0 matches, nonzero)" 1 "$rc"

# (c-multi) MULTIPLE MATCHES: duplicate the sink line -> ambiguous -> REFUSE.
write_canonical_sink
write_baseline
awk '{print} /Files.newInputStream\(sinkPath\);/{print}' "$repo/src/Sink.java" > "$repo/src/Sink.java.tmp"
mv "$repo/src/Sink.java.tmp" "$repo/src/Sink.java"
rc="$(run_tool)"
expect_rc "duplicated sink line refuses (multiple matches, nonzero)" 1 "$rc"

# (d) UNCHANGED FILE: no working-tree change -> no drift, exit 0, id untouched.
write_canonical_sink
write_baseline
rc="$(run_tool)"
expect_rc "unchanged file -> no remap (exit 0)" 0 "$rc"
assert_id "unchanged file leaves id at 5:5" "java/path-injection@src/Sink.java:5:5"

# (e) SAME-LINE NO-OP: edit a different line in place (no line-count change) -> file
#     changed but the sink still resolves to line 5 -> no-op, exit 0, id untouched.
write_canonical_sink
write_baseline
sed -i 's/void go(java.nio.file.Path sinkPath) throws Exception {/void go(java.nio.file.Path sinkPath, int extra) throws Exception {/' "$repo/src/Sink.java"
rc="$(run_tool)"
expect_rc "sink stays on line 5 despite edit elsewhere -> no-op (exit 0)" 0 "$rc"
assert_id "same-line no-op leaves id at 5:5" "java/path-injection@src/Sink.java:5:5"

# (f) NON-UNIQUE AT OLD-REV: the old file already had TWO byte-identical sink lines
#     (the real baselined sink + an unrelated textual twin). The real sink's content
#     then changes while the twin survives, so the CURRENT file shows exactly ONE
#     match — the twin. Remapping to it would baseline an unrelated location while the
#     actually-changed finding slips the gate. The tool MUST refuse (ambiguous at
#     old-rev), not mis-remap. Needs its own committed old-rev, so use a second repo.
repo2="$tmp_dir/repo2"
mkdir -p "$repo2/src"
cat > "$repo2/src/Twin.java" <<'JAVA'
package x;

class Twin {
  void go(java.nio.file.Path sinkPath) throws Exception {
    Files.newInputStream(sinkPath);
    audit();
    Files.newInputStream(sinkPath);
  }
}
JAVA
cat > "$repo2/baseline.json" <<'JSON'
{
  "schema": {
    "required": ["id", "finding", "reason", "owner", "introduced", "expires", "compensating_control", "removal_issue"]
  },
  "findings": [
    {
      "id": "java/path-injection@src/Twin.java:5:5",
      "finding": "fixture twin sink",
      "reason": "Triaged already-mitigated (fixture).",
      "owner": "tester",
      "introduced": "2026-07-18",
      "expires": "2026-10-18",
      "compensating_control": "n/a fixture",
      "removal_issue": "#0"
    }
  ]
}
JSON
git -C "$repo2" init -q
git -C "$repo2" add src/Twin.java baseline.json
git -C "$repo2" -c user.email=t@example.invalid -c user.name=tester commit -q -m "old-rev twin"
OLD_REV2="$(git -C "$repo2" rev-parse HEAD)"
# Change ONLY the baselined sink (line 5); the identical twin on line 7 survives.
sed -i '5s/.*/    Files.newInputStream(changedPath);/' "$repo2/src/Twin.java"
set +e
node "$tool" "$OLD_REV2" --baseline "$repo2/baseline.json" --root "$repo2" >"$tmp_dir/out.log" 2>&1
rc2=$?
set -e
expect_rc "non-unique sink at old-rev refuses (no mis-remap to the twin)" 1 "$rc2"
if grep -qF '"java/path-injection@src/Twin.java:7:5"' "$repo2/baseline.json"; then
  echo "FAIL - tool mis-remapped to the surviving twin at line 7"
  fail=$((fail + 1))
else
  echo "ok   - twin location (line 7) was NOT baselined"
  pass=$((pass + 1))
fi

echo
echo "Passed: $pass, Failed: $fail"
[[ "$fail" -eq 0 ]]
