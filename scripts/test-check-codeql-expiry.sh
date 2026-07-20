#!/usr/bin/env bash
# Fixture tests for check-codeql-expiry.py. Builds baselines with known expiry dates,
# evaluates them against a fixed --today, and asserts the reported status, the chosen
# earliest expiry, and the exit codes (0 for ok/warn/expired, 2 for a malformed baseline).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
checker="$script_dir/check-codeql-expiry.py"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/check-codeql-expiry.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

pass=0
fail=0

# Write a baseline whose findings carry the given expiry dates (one arg per entry).
write_baseline() {
  local path="$1"; shift
  {
    printf '{\n  "findings": [\n'
    local first=1
    for exp in "$@"; do
      [ $first -eq 0 ] && printf ',\n'
      first=0
      printf '    {"id": "r@f:1:1", "expires": "%s"}' "$exp"
    done
    printf '\n  ]\n}\n'
  } > "$path"
}

# Run the checker with a captured GITHUB_OUTPUT and echo "<rc> <status>".
run_check() {
  local baseline="$1" today="$2" lead="${3:-45}"
  local out="$tmp_dir/gh_out"; : > "$out"
  set +e
  GITHUB_OUTPUT="$out" python3 "$checker" --baseline "$baseline" --today "$today" --lead-days "$lead" >"$tmp_dir/stdout" 2>&1
  local rc=$?
  set -e
  local status
  status="$(sed -n 's/^status=//p' "$out")"
  echo "$rc ${status:-<none>}"
}

check_case() {
  local desc="$1" expect_rc="$2" expect_status="$3" got="$4"
  local rc="${got%% *}" status="${got#* }"
  if [[ "$rc" -eq "$expect_rc" && "$status" == "$expect_status" ]]; then
    echo "ok   - $desc (rc=$rc status=$status)"
    pass=$((pass + 1))
  else
    echo "FAIL - $desc (expected rc=$expect_rc status=$expect_status; got rc=$rc status=$status)"
    sed 's/^/       /' "$tmp_dir/stdout"
    fail=$((fail + 1))
  fi
}

# OK: expiry well beyond the lead window.
write_baseline "$tmp_dir/ok.json" 2026-10-15
check_case "far-off expiry -> ok" 0 ok "$(run_check "$tmp_dir/ok.json" 2026-07-20 45)"

# WARN: expiry inside the 45-day lead window.
write_baseline "$tmp_dir/warn.json" 2026-10-15
check_case "inside lead window -> warn" 0 warn "$(run_check "$tmp_dir/warn.json" 2026-09-05 45)"

# BOUNDARY: exactly lead-days away -> still warn.
write_baseline "$tmp_dir/boundary.json" 2026-10-15
check_case "exactly lead-days away -> warn" 0 warn "$(run_check "$tmp_dir/boundary.json" 2026-08-31 45)"

# EXPIRED: expiry already in the past.
write_baseline "$tmp_dir/expired.json" 2026-10-15
check_case "past expiry -> expired" 0 expired "$(run_check "$tmp_dir/expired.json" 2026-10-20 45)"

# EARLIEST WINS: mix of dates; the earliest drives the status.
write_baseline "$tmp_dir/earliest.json" 2027-01-01 2026-09-10 2026-12-01
check_case "earliest expiry drives status (warn)" 0 warn "$(run_check "$tmp_dir/earliest.json" 2026-08-15 45)"
if grep -qF 'min_expiry=2026-09-10' "$tmp_dir/gh_out"; then
  echo "ok   - earliest expiry (2026-09-10) reported as min_expiry"; pass=$((pass + 1))
else
  echo "FAIL - min_expiry did not report the earliest date"; fail=$((fail + 1))
fi

# STRUCTURAL: a finding missing its expires date -> exit 2, no status.
printf '{"findings":[{"id":"r@f:1:1"}]}\n' > "$tmp_dir/bad.json"
check_case "missing expires -> structural failure" 2 "<none>" "$(run_check "$tmp_dir/bad.json" 2026-07-20 45)"

echo
echo "Passed: $pass, Failed: $fail"
[[ "$fail" -eq 0 ]]
