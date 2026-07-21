#!/usr/bin/env bash
# Synthetic-fixture tests for check-codeql-baseline-drift.mjs (the fifth mirror gate).
#
# The gate compares each entry's coordinate line against its STORED sink_line fingerprint,
# working tree only -- no git. So the core fixtures are plain files (a source file + a
# baseline JSON), no repo needed:
#
#   (a) no drift             -> the pinned line still matches sink_line       -> exit 0
#   (b) shifted line         -> a line inserted above the sink                -> exit 1, names entry
#   (c) metadata-only edit    -> reason changed, sink_line unchanged, source
#                               shifted (the blocker-1 walk that used to
#                               false-green under git-derivation)             -> exit 1, names entry
#   (d) missing fingerprint  -> entry has no sink_line                        -> exit 1 (fail closed)
#   (e) file shrank          -> file is shorter than the pinned line          -> exit 1
#   (f) malformed id         -> id is not <ruleId>@<uri>:<l>:<c>              -> exit 1
#
# Finally it exercises the WIRED gate end-to-end by running check-release-mirrors.sh
# against the real repo and asserting exit 0 on the clean tree.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
gate="$script_dir/check-codeql-baseline-drift.mjs"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/check-codeql-drift.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

pass=0
fail=0

# Write a source file whose sink (Files.newInputStream) sits on line 5.
write_sink() {
  local dir="$1"
  mkdir -p "$dir/src"
  cat > "$dir/src/Sink.java" <<'JAVA'
package x;

class Sink {
  void go(java.nio.file.Path sinkPath) throws Exception {
    Files.newInputStream(sinkPath);
  }
}
JAVA
}

# Write a one-entry baseline. $2 = sink_line value; $3 = reason (default fixture text);
# pass the literal token OMIT_SINK as $2 to omit the sink_line field entirely.
write_baseline() {
  local dir="$1" sink="$2" reason="${3:-Triaged already-mitigated (fixture).}"
  local sink_field="\"sink_line\": $(json_str "$sink"),"
  [[ "$sink" == "OMIT_SINK" ]] && sink_field=""
  cat > "$dir/baseline.json" <<JSON
{
  "schema": {
    "required": ["id", "sink_line", "finding", "reason", "owner", "introduced", "expires", "compensating_control", "removal_issue"]
  },
  "findings": [
    {
      "id": "java/path-injection@src/Sink.java:5:5",
      $sink_field
      "finding": "fixture sink",
      "reason": $(json_str "$reason"),
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

# JSON-encode a string via node (handles quotes/backslashes safely).
json_str() { node -e 'process.stdout.write(JSON.stringify(process.argv[1]))' "$1"; }

run_gate() {
  local dir="$1"
  set +e
  node "$gate" --root "$dir" --baseline "$dir/baseline.json" >"$tmp_dir/out.log" 2>&1
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

expect_output() {
  local desc="$1" needle="$2"
  if grep -qF "$needle" "$tmp_dir/out.log"; then
    echo "ok   - $desc"
    pass=$((pass + 1))
  else
    echo "FAIL - $desc (\"$needle\" not in output)"
    sed 's/^/       /' "$tmp_dir/out.log"
    fail=$((fail + 1))
  fi
}

SINK='    Files.newInputStream(sinkPath);'

# (a) NO DRIFT: the pinned line still holds the sink -> exit 0.
a="$tmp_dir/a"; write_sink "$a"; write_baseline "$a" "$SINK"
rc="$(run_gate "$a")"
expect_rc "no-drift fixture passes" 0 "$rc"

# (b) SHIFTED LINE: insert 3 blank lines above the sink. The pinned line now holds a blank
#     line, not the fingerprint -> drift, exit 1, entry named, refresh tool referenced.
b="$tmp_dir/b"; write_sink "$b"; write_baseline "$b" "$SINK"
{ printf '\n\n\n'; cat "$b/src/Sink.java"; } > "$b/src/Sink.java.tmp"
mv "$b/src/Sink.java.tmp" "$b/src/Sink.java"
rc="$(run_gate "$b")"
expect_rc "shifted-line fixture fails" 1 "$rc"
expect_output "drift output names the drifted entry" "java/path-injection@src/Sink.java:5:5"
expect_output "drift output points at the refresh tool" "refresh-codeql-baseline.mjs"

# (c) METADATA-ONLY EDIT (the blocker-1 walk): the baseline's `reason` is edited (as the
#     expiry tooling would) while sink_line is unchanged, and the source has shifted. Under
#     the old git-derivation this advanced the derivation rev to already-shifted source and
#     false-greened forever. With a stored fingerprint the shift is caught regardless of the
#     metadata edit -> exit 1.
c="$tmp_dir/c"; write_sink "$c"
write_baseline "$c" "$SINK" "Re-triaged and expiry bumped (metadata-only edit)."
{ printf '\n\n'; cat "$c/src/Sink.java"; } > "$c/src/Sink.java.tmp"
mv "$c/src/Sink.java.tmp" "$c/src/Sink.java"
rc="$(run_gate "$c")"
expect_rc "metadata-only edit does not hide a source shift" 1 "$rc"
expect_output "metadata-edit case names the entry" "java/path-injection@src/Sink.java:5:5"

# (d) MISSING FINGERPRINT: an entry without sink_line fails closed (never passes unchecked).
d="$tmp_dir/d"; write_sink "$d"; write_baseline "$d" "OMIT_SINK"
rc="$(run_gate "$d")"
expect_rc "missing sink_line fails closed" 1 "$rc"
expect_output "missing-fingerprint output names the entry" "java/path-injection@src/Sink.java:5:5"

# (e) FILE SHRANK: source shorter than the pinned line -> exit 1.
e="$tmp_dir/e"; write_sink "$e"; write_baseline "$e" "$SINK"
printf 'package x;\n' > "$e/src/Sink.java"
rc="$(run_gate "$e")"
expect_rc "file shorter than pinned line fails" 1 "$rc"

# (f) MALFORMED ID: an id that is not <ruleId>@<uri>:<line>:<col> is unverifiable -> exit 1.
f="$tmp_dir/f"; write_sink "$f"
cat > "$f/baseline.json" <<'JSON'
{
  "schema": {
    "required": ["id", "sink_line", "finding", "reason", "owner", "introduced", "expires", "compensating_control", "removal_issue"]
  },
  "findings": [
    {
      "id": "not-a-valid-identity",
      "sink_line": "    Files.newInputStream(sinkPath);",
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
rc="$(run_gate "$f")"
expect_rc "malformed id fails" 1 "$rc"

# (g) WIRED END-TO-END: the fifth gate runs inside check-release-mirrors.sh on the real
#     repo. On the clean tree the whole runner must exit 0.
set +e
bash "$repo_root/scripts/check-release-mirrors.sh" >"$tmp_dir/runner.log" 2>&1
rc_runner=$?
set -e
if [[ "$rc_runner" -eq 0 ]]; then
  echo "ok   - check-release-mirrors.sh runs the wired gate and passes on the clean tree (rc=0)"
  pass=$((pass + 1))
else
  echo "FAIL - check-release-mirrors.sh returned rc=$rc_runner on the clean tree"
  sed 's/^/       /' "$tmp_dir/runner.log"
  fail=$((fail + 1))
fi
if grep -qF "CodeQL baseline drift" "$tmp_dir/runner.log"; then
  echo "ok   - runner output includes the CodeQL baseline drift gate section"
  pass=$((pass + 1))
else
  echo "FAIL - runner output missing the CodeQL baseline drift gate section"
  fail=$((fail + 1))
fi

echo
echo "Passed: $pass, Failed: $fail"
[[ "$fail" -eq 0 ]]
