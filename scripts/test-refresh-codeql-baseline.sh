#!/usr/bin/env bash
# Synthetic-fixture tests for refresh-codeql-baseline.mjs. Builds a throwaway git
# repo (an isolated fixture, NOT the project repo) with a known sink file committed
# at an "old-rev", then mutates the working tree to reproduce each drift class and
# asserts the tool: remaps a byte-identical shift; leaves an entry it cannot map
# UNCHANGED and reports it for manual follow-up (exit 3) instead of aborting; and,
# crucially, still remaps the mappable entries when another entry in the same run is
# unmappable (per-entry degradation, not all-or-nothing).
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
  local desc="$1" expected_id="$2" file="${3:-$repo/baseline.json}"
  if grep -qF "\"$expected_id\"" "$file"; then
    echo "ok   - $desc"
    pass=$((pass + 1))
  else
    echo "FAIL - $desc (id \"$expected_id\" not in baseline)"
    fail=$((fail + 1))
  fi
}

refute_id() {
  local desc="$1" absent_id="$2" file="${3:-$repo/baseline.json}"
  if grep -qF "\"$absent_id\"" "$file"; then
    echo "FAIL - $desc (id \"$absent_id\" unexpectedly present)"
    fail=$((fail + 1))
  else
    echo "ok   - $desc"
    pass=$((pass + 1))
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
#     cannot map -> skip-with-report (exit 3), baseline untouched.
write_canonical_sink
write_baseline
sed -i 's/Files.newInputStream(sinkPath);/Files.newInputStream(otherPath);/' "$repo/src/Sink.java"
rc="$(run_tool)"
expect_rc "changed sink content -> manual follow-up (exit 3)" 3 "$rc"
assert_id "changed-content baseline left untouched at 5:5" "java/path-injection@src/Sink.java:5:5"

# (c-zero) 0 MATCHES: delete the sink line entirely -> skip-with-report (exit 3).
write_canonical_sink
write_baseline
sed -i '/Files.newInputStream(sinkPath);/d' "$repo/src/Sink.java"
rc="$(run_tool)"
expect_rc "deleted sink line -> manual follow-up (0 matches, exit 3)" 3 "$rc"

# (c-multi) MULTIPLE MATCHES: duplicate the sink line -> ambiguous -> skip-with-report.
write_canonical_sink
write_baseline
awk '{print} /Files.newInputStream\(sinkPath\);/{print}' "$repo/src/Sink.java" > "$repo/src/Sink.java.tmp"
mv "$repo/src/Sink.java.tmp" "$repo/src/Sink.java"
rc="$(run_tool)"
expect_rc "duplicated sink line -> manual follow-up (multiple matches, exit 3)" 3 "$rc"

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
#     actually-changed finding slips the gate. The tool MUST leave it for manual
#     follow-up (ambiguous at old-rev), not mis-remap. Needs its own committed old-rev,
#     so use a second repo.
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
expect_rc "non-unique sink at old-rev -> manual follow-up (no mis-remap to the twin)" 3 "$rc2"
refute_id "twin location (line 7) was NOT baselined" "java/path-injection@src/Twin.java:7:5" "$repo2/baseline.json"
assert_id "non-unique entry left untouched at 5:5" "java/path-injection@src/Twin.java:5:5" "$repo2/baseline.json"

# (g) MIXED: the load-bearing case for the loosening. A baseline with TWO entries:
#     one whose sink cleanly shifts (must remap) and one whose sink content changed
#     (cannot map). The unmappable entry must NOT block the mappable one — the run
#     must remap the good entry AND leave the bad one untouched, exiting 3.
repo3="$tmp_dir/repo3"
mkdir -p "$repo3/src"
cat > "$repo3/src/Good.java" <<'JAVA'
package x;

class Good {
  void go(java.nio.file.Path goodPath) throws Exception {
    Files.newInputStream(goodPath);
  }
}
JAVA
cat > "$repo3/src/Bad.java" <<'JAVA'
package x;

class Bad {
  void go(java.nio.file.Path badPath) throws Exception {
    Files.newInputStream(badPath);
  }
}
JAVA
cat > "$repo3/baseline.json" <<'JSON'
{
  "schema": {
    "required": ["id", "finding", "reason", "owner", "introduced", "expires", "compensating_control", "removal_issue"]
  },
  "findings": [
    {
      "id": "java/path-injection@src/Good.java:5:5",
      "finding": "fixture good sink",
      "reason": "Triaged already-mitigated (fixture).",
      "owner": "tester",
      "introduced": "2026-07-18",
      "expires": "2026-10-18",
      "compensating_control": "n/a fixture",
      "removal_issue": "#0"
    },
    {
      "id": "java/path-injection@src/Bad.java:5:5",
      "finding": "fixture bad sink",
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
git -C "$repo3" init -q
git -C "$repo3" add src/Good.java src/Bad.java baseline.json
git -C "$repo3" -c user.email=t@example.invalid -c user.name=tester commit -q -m "old-rev mixed"
OLD_REV3="$(git -C "$repo3" rev-parse HEAD)"
# Good.java drifts (2 blank lines above the sink: 5 -> 7, byte-identical).
{ printf '\n\n'; cat "$repo3/src/Good.java"; } > "$repo3/src/Good.java.tmp"
mv "$repo3/src/Good.java.tmp" "$repo3/src/Good.java"
# Bad.java's sink content changes in place -> cannot map.
sed -i 's/Files.newInputStream(badPath);/Files.newInputStream(elsewhere);/' "$repo3/src/Bad.java"
set +e
node "$tool" "$OLD_REV3" --baseline "$repo3/baseline.json" --root "$repo3" >"$tmp_dir/out.log" 2>&1
rc3=$?
set -e
expect_rc "mixed run: unmappable entry does not abort the mappable one (exit 3)" 3 "$rc3"
assert_id "mixed run: Good.java entry WAS remapped to line 7" "java/path-injection@src/Good.java:7:5" "$repo3/baseline.json"
assert_id "mixed run: Bad.java entry left untouched at 5:5" "java/path-injection@src/Bad.java:5:5" "$repo3/baseline.json"
refute_id "mixed run: Good.java old coordinate is gone" "java/path-injection@src/Good.java:5:5" "$repo3/baseline.json"

# (h) INTERACTIVE: a duplicated sink (2 matches) is unmappable non-interactively, but
#     with --interactive the operator picks the intended line (validated byte-identical)
#     and it is remapped. Feed the chosen line on stdin.
write_canonical_sink
write_baseline
awk '{print} /Files.newInputStream\(sinkPath\);/{print}' "$repo/src/Sink.java" > "$repo/src/Sink.java.tmp"
mv "$repo/src/Sink.java.tmp" "$repo/src/Sink.java"
set +e
printf '6\n' | node "$tool" "$OLD_REV" --baseline "$repo/baseline.json" --root "$repo" --interactive >"$tmp_dir/out.log" 2>&1
rc_h=$?
set -e
expect_rc "interactive: operator-chosen line remaps the ambiguous entry (exit 0)" 0 "$rc_h"
assert_id "interactive: entry remapped to the chosen line 6" "java/path-injection@src/Sink.java:6:5"

echo
echo "Passed: $pass, Failed: $fail"
[[ "$fail" -eq 0 ]]
