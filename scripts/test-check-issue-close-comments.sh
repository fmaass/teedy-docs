#!/usr/bin/env bash
set -euo pipefail

# Self-test for scripts/check-issue-close-comments.sh.
#
# The checker takes its candidate issues from two sources — GitHub's list of
# issues closed inside the release window (source A) and every issue number the
# delta's commit messages mention (source B) — so the fixtures below drive both:
# a throwaway git repo supplies the window and the references, and a `gh` shim on
# PATH answers `repo view`, `issue list`, `issue view` and the comments API from
# per-scenario fixture files. The comment fixtures also straddle the checker's
# fifteen-minute grace window in both directions, since a close comment written
# just before the close is the ceremony's normal case, not an exception.

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
checker="$script_dir/check-issue-close-comments.sh"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/check-issue-close-comments.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

repo="$tmp_dir/repo"
shim_dir="$tmp_dir/bin"
mkdir -p "$repo" "$shim_dir"

git -C "$repo" init -q
git -C "$repo" config user.name "Fixture Author"
git -C "$repo" config user.email "fixture@example.invalid"

fixture_commit() {
  local date="$1" message="$2"
  printf '%s\n' "$message" >> "$repo/history.txt"
  git -C "$repo" add history.txt
  GIT_AUTHOR_DATE="$date" GIT_COMMITTER_DATE="$date" \
    git -C "$repo" commit -q -m "$message"
}

# The window is pinned by the fixture commit dates: lightweight tags take their
# creatordate from the commit they point at.
from_bound='2026-07-01T00:00:00+00:00'
plain_bound='2026-07-05T00:00:00+00:00'
ref_bound='2026-07-06T00:00:00+00:00'

fixture_commit "$from_bound" "base"
git -C "$repo" tag prev-release

fixture_commit "$plain_bound" "a change that names no issue at all"
git -C "$repo" tag head-plain

fixture_commit "$ref_bound" $'prose mentions are enough (#7)\n\nno trailer here'
git -C "$repo" tag head-ref

cat > "$shim_dir/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$*" >> "$GH_CALL_LOG"

unknown_flag() {
  echo "unknown flag: $1" >&2
  exit 2
}

if [[ "${1:-}" == "repo" && "${2:-}" == "view" ]]; then
  if (( $# != 4 )) || [[ "$3" != "--json" || "$4" != "nameWithOwner,owner" ]]; then
    for arg in "${@:3}"; do
      [[ "$arg" == -* ]] && unknown_flag "$arg"
    done
    echo "Unexpected gh invocation: $*" >&2
    exit 2
  fi
  printf '%s\n' '{"nameWithOwner":"maintainer/project","owner":{"login":"maintainer"}}'
  exit 0
fi

if [[ "${1:-}" == "issue" && "${2:-}" == "list" ]]; then
  if (( $# != 10 )) || [[ "$3" != "--state" || "$4" != "closed" || "$5" != "--search" || \
      "$7" != "--limit" || ! "$8" =~ ^[0-9]+$ || "$9" != "--json" || "${10}" != "number,title,state,closedAt" ]]; then
    for arg in "${@:3}"; do
      case "$arg" in
        --state|--search|--limit|--json) ;;
        -*) unknown_flag "$arg" ;;
      esac
    done
    echo "Unexpected gh invocation: $*" >&2
    exit 2
  fi
  printf '%s\n' "$6" > "$GH_SEARCH_LOG"
  cat "$GH_FIXTURE_DIR/issue-list.json"
  exit 0
fi

if [[ "${1:-}" == "issue" && "${2:-}" == "view" ]]; then
  if (( $# != 5 )) || [[ "$4" != "--json" || "$5" != "title,state,closedAt" ]]; then
    for arg in "${@:3}"; do
      [[ "$arg" == -* ]] && unknown_flag "$arg"
    done
    echo "Unexpected gh invocation: $*" >&2
    exit 2
  fi
  error_file="$GH_FIXTURE_DIR/issue-$3.error"
  if [[ -f "$error_file" ]]; then
    cat "$error_file" >&2
    exit 1
  fi
  issue_file="$GH_FIXTURE_DIR/issue-$3.json"
  if [[ ! -f "$issue_file" ]]; then
    echo "GraphQL: Could not resolve to an issue or pull request with the number of $3." >&2
    exit 1
  fi
  cat "$issue_file"
  exit 0
fi

if [[ "${1:-}" == "api" ]]; then
  if (( $# != 3 )) || [[ "$2" != "--paginate" ]] || \
      [[ ! "$3" =~ ^repos/maintainer/project/issues/[0-9]+/comments$ ]]; then
    for arg in "${@:2}"; do
      [[ "$arg" == -* && "$arg" != "--paginate" ]] && unknown_flag "$arg"
    done
    echo "Unexpected gh invocation: $*" >&2
    exit 2
  fi
  issue_number="${3#repos/maintainer/project/issues/}"
  issue_number="${issue_number%/comments}"
  comments_file="$GH_FIXTURE_DIR/comments-$issue_number.json"
  if [[ -f "$comments_file" ]]; then
    cat "$comments_file"
  else
    printf '%s\n' '[]'
  fi
  exit 0
fi

echo "Unexpected gh invocation: $*" >&2
exit 2
EOF
chmod +x "$shim_dir/gh"

export PATH="$shim_dir:$PATH"
export GH_CALL_LOG="$tmp_dir/gh-calls.log"
export GH_SEARCH_LOG="$tmp_dir/gh-search.log"

set +e
GH_FIXTURE_MODE=after "$shim_dir/gh" api --paginate --slurp \
  repos/maintainer/project/issues/1/comments >/dev/null 2>&1
shim_status=$?
set -e
if (( shim_status != 2 )); then
  echo "FAIL: gh shim did not reject an unknown flag with exit 2" >&2
  exit 1
fi

fixture_dir=""
output_file=""
scenario_limit=""
run_started_at=0
run_finished_at=0

scenario() {
  local name="$1"
  fixture_dir="$tmp_dir/fixtures/$name"
  output_file="$tmp_dir/$name.out"
  scenario_limit=""
  mkdir -p "$fixture_dir"
  printf '%s\n' '[]' > "$fixture_dir/issue-list.json"
  : > "$GH_CALL_LOG"
  : > "$GH_SEARCH_LOG"
}

window_end_of() {
  awk '/^window /{separator = index($2, ".."); print substr($2, separator + 2); exit}' "$output_file"
}

run_status=0

run_checker() {
  local head="$1"
  run_started_at="$(date -u +%s)"
  set +e
  # `env` rather than a bare assignment prefix: a prefix produced by expansion is a
  # command word, not an assignment.
  (cd "$repo" && GH_FIXTURE_DIR="$fixture_dir" \
    env ${scenario_limit:+ISSUE_LIST_LIMIT=$scenario_limit} \
    bash "$checker" prev-release "$head") >"$output_file" 2>&1
  run_status=$?
  set -e
  run_finished_at="$(date -u +%s)"
}

expect_status() {
  local label="$1" expected="$2"
  if (( run_status != expected )); then
    echo "FAIL: $label exited $run_status, expected $expected" >&2
    cat "$output_file" >&2
    exit 1
  fi
}

expect_line() {
  local label="$1" needle="$2"
  if ! grep -Fq -- "$needle" "$output_file"; then
    echo "FAIL: $label did not report: $needle" >&2
    cat "$output_file" >&2
    exit 1
  fi
}

expect_no_line() {
  local label="$1" needle="$2"
  if grep -Fq -- "$needle" "$output_file"; then
    echo "FAIL: $label unexpectedly reported: $needle" >&2
    cat "$output_file" >&2
    exit 1
  fi
}

expect_call() {
  local label="$1" needle="$2"
  if ! grep -Fq -- "$needle" "$GH_CALL_LOG"; then
    echo "FAIL: $label did not call gh: $needle" >&2
    cat "$GH_CALL_LOG" >&2
    exit 1
  fi
}

expect_no_call() {
  local label="$1" needle="$2"
  if grep -Fq -- "$needle" "$GH_CALL_LOG"; then
    echo "FAIL: $label unexpectedly called gh: $needle" >&2
    cat "$GH_CALL_LOG" >&2
    exit 1
  fi
}

report() {
  echo "PASS: $1"
  sed 's/^/    /' "$output_file"
}

violation='#1 FAIL — Issue one — closed 2026-07-03T12:00:00Z, no maintainer comment as part of the close (none since 2026-07-03T11:45:00Z)'

# (a) An issue closed inside the window, with a maintainer comment after the close.
label="closed in window, maintainer comment after close"
scenario window-ok
cat > "$fixture_dir/issue-list.json" <<'EOF'
[{"number":1,"title":"Issue one","state":"CLOSED","closedAt":"2026-07-03T12:00:00Z"}]
EOF
cat > "$fixture_dir/comments-1.json" <<'EOF'
[{"user":{"login":"contributor"},"created_at":"2026-07-03T12:00:01Z"},
 {"user":{"login":"maintainer"},"created_at":"2026-07-03T12:00:02Z"}]
EOF
run_checker head-plain
expect_status "$label" 0
expect_line "$label" "window $from_bound..$plain_bound (prev-release..head-plain)"
expect_line "$label" '#1 ok — Issue one (closed 2026-07-03T12:00:00Z)'
expect_line "$label" 'checked 1 issues: 1 ok, 0 failing, 0 pending close, 0 skipped (pre-window)'
if [[ "$(cat "$GH_SEARCH_LOG")" != "closed:$from_bound..$plain_bound" ]]; then
  echo "FAIL: $label searched the wrong window: $(cat "$GH_SEARCH_LOG")" >&2
  exit 1
fi
report "$label"

# (b) Positive control: the last maintainer comment predates the close by more than
#     the grace window, so nothing on the issue explains the close.
label="closed in window, maintainer comment 20 minutes before the close"
scenario window-before
cat > "$fixture_dir/issue-list.json" <<'EOF'
[{"number":1,"title":"Issue one","state":"CLOSED","closedAt":"2026-07-03T12:00:00Z"}]
EOF
cat > "$fixture_dir/comments-1.json" <<'EOF'
[{"user":{"login":"maintainer"},"created_at":"2026-07-03T11:40:00Z"}]
EOF
run_checker head-plain
expect_status "$label" 1
expect_line "$label" "$violation"
expect_line "$label" 'checked 1 issues: 0 ok, 1 failing, 0 pending close, 0 skipped (pre-window)'
report "$label"

# (i) The ceremony's real order: the explanation is written, then the issue is closed
#     seconds later. Inside the grace window that comment IS the close comment.
label="closed in window, maintainer comment 30 seconds before the close"
scenario window-grace
cat > "$fixture_dir/issue-list.json" <<'EOF'
[{"number":1,"title":"Issue one","state":"CLOSED","closedAt":"2026-07-03T12:00:00Z"}]
EOF
cat > "$fixture_dir/comments-1.json" <<'EOF'
[{"user":{"login":"maintainer"},"created_at":"2026-07-03T11:59:30Z"}]
EOF
run_checker head-plain
expect_status "$label" 0
expect_line "$label" '#1 ok — Issue one (closed 2026-07-03T12:00:00Z)'
expect_line "$label" 'checked 1 issues: 1 ok, 0 failing, 0 pending close, 0 skipped (pre-window)'
report "$label"

# (b2) Positive control: the post-close comment is somebody else's.
label="closed in window, post-close comment by a non-maintainer"
scenario window-other
cat > "$fixture_dir/issue-list.json" <<'EOF'
[{"number":1,"title":"Issue one","state":"CLOSED","closedAt":"2026-07-03T12:00:00Z"}]
EOF
cat > "$fixture_dir/comments-1.json" <<'EOF'
[{"user":{"login":"contributor"},"created_at":"2026-07-03T12:00:01Z"}]
EOF
run_checker head-plain
expect_status "$label" 1
expect_line "$label" "$violation"
report "$label"

# (c) The v3.8.9 shape: referenced only in a commit message, closed AFTER the
#     window's end (the ceremony closes issues once the release exists) — still checked.
label="referenced in the delta, closed after the window end"
scenario reference-after-window
cat > "$fixture_dir/issue-7.json" <<'EOF'
{"title":"Issue seven","state":"CLOSED","closedAt":"2026-07-20T12:00:00Z"}
EOF
cat > "$fixture_dir/comments-7.json" <<'EOF'
[{"user":{"login":"maintainer"},"created_at":"2026-07-20T12:00:01Z"}]
EOF
run_checker head-ref
expect_status "$label" 0
expect_call "$label" 'issue view 7 --json title,state,closedAt'
expect_line "$label" '#7 ok — Issue seven (closed 2026-07-20T12:00:00Z)'
expect_line "$label" 'checked 1 issues: 1 ok, 0 failing, 0 pending close, 0 skipped (pre-window)'
report "$label"

# (d) Referenced as context but closed by an EARLIER release — skipped, not failing.
label="referenced in the delta, closed before the window"
scenario reference-before-window
cat > "$fixture_dir/issue-7.json" <<'EOF'
{"title":"Issue seven","state":"CLOSED","closedAt":"2026-06-01T12:00:00Z"}
EOF
run_checker head-ref
expect_status "$label" 0
expect_line "$label" "#7 closed before the window (not this release's) — skipped"
expect_line "$label" 'checked 1 issues: 0 ok, 0 failing, 0 pending close, 1 skipped (pre-window)'
expect_no_call "$label" 'issues/7/comments'
report "$label"

# (e) Nothing closed in the window and nothing referenced: reported, never a silent exit 0.
label="no closed issues and no references"
scenario empty
run_checker head-plain
expect_status "$label" 0
expect_line "$label" "0 issues found in $from_bound..$plain_bound (and none referenced in prev-release..head-plain)"
expect_call "$label" 'issue list --state closed'
report "$label"

# (f) A referenced issue that is still open: pending, not a failure.
label="referenced issue still open"
scenario reference-open
cat > "$fixture_dir/issue-7.json" <<'EOF'
{"title":"Issue seven","state":"OPEN","closedAt":null}
EOF
run_checker head-ref
expect_status "$label" 0
expect_line "$label" '#7 pending close — will need a close comment'
expect_line "$label" 'checked 1 issues: 0 ok, 0 failing, 1 pending close, 0 skipped (pre-window)'
expect_no_call "$label" 'issues/7/comments'
report "$label"

# (g) A number in prose that is no issue of this repository is ignored, not fatal.
label="referenced number that is not an issue"
scenario reference-missing
run_checker head-ref
expect_status "$label" 0
expect_line "$label" '#7 referenced in the delta but not a repository issue — ignored'
expect_line "$label" "0 issues found in $from_bound..$ref_bound"
report "$label"

# (h) An issue in BOTH sources is checked once, from the listing.
label="issue in both sources is deduplicated"
scenario both-sources
cat > "$fixture_dir/issue-list.json" <<'EOF'
[{"number":7,"title":"Issue seven","state":"CLOSED","closedAt":"2026-07-03T12:00:00Z"}]
EOF
cat > "$fixture_dir/comments-7.json" <<'EOF'
[{"user":{"login":"maintainer"},"created_at":"2026-07-03T12:00:01Z"}]
EOF
run_checker head-ref
expect_status "$label" 0
expect_line "$label" '#7 ok — Issue seven (closed 2026-07-03T12:00:00Z)'
expect_line "$label" 'checked 1 issues: 1 ok, 0 failing, 0 pending close, 0 skipped (pre-window)'
expect_no_call "$label" 'issue view 7'
expect_no_line "$label" 'not a repository issue'
report "$label"

# (j) A head ref that is NOT a tag runs to now, even when a tag already contains it:
#     ending at the commit's own date would hide every issue closed since that commit.
label="unreleased head ref runs the window to now"
scenario head-not-a-tag
run_checker "$(git -C "$repo" rev-parse head-plain)"
expect_status "$label" 0
expect_no_line "$label" "..$plain_bound "
window_end_epoch="$(date -u -d "$(window_end_of)" +%s)"
if (( window_end_epoch < run_started_at || window_end_epoch > run_finished_at )); then
  echo "FAIL: $label ended the window at $(window_end_of), not at the moment of the run" >&2
  cat "$output_file" >&2
  exit 1
fi
report "$label"

# (k) A lookup that fails for any reason other than "no such issue" stops the gate:
#     a dropped candidate on a rate limit would be a false green.
label="referenced issue lookup fails transiently"
scenario reference-transient-failure
printf '%s\n' 'HTTP 403: API rate limit exceeded (https://api.github.com/graphql)' \
  > "$fixture_dir/issue-7.error"
run_checker head-ref
expect_status "$label" 1
expect_line "$label" 'error: gh issue view 7 failed: HTTP 403: API rate limit exceeded'
expect_no_line "$label" 'not a repository issue'
report "$label"

# (l) Mixed timestamp forms: an offset-form comment 20 minutes before an offset-form
#     close is outside the grace window — a lexical comparison would call it ok.
label="offset-form comment 20 minutes before an offset-form close"
scenario offset-before
cat > "$fixture_dir/issue-list.json" <<'EOF'
[{"number":1,"title":"Issue one","state":"CLOSED","closedAt":"2026-07-03T12:00:00+02:00"}]
EOF
cat > "$fixture_dir/comments-1.json" <<'EOF'
[{"user":{"login":"maintainer"},"created_at":"2026-07-03T11:40:00+02:00"}]
EOF
run_checker head-plain
expect_status "$label" 1
expect_line "$label" '#1 FAIL — Issue one — closed 2026-07-03T12:00:00+02:00, no maintainer comment as part of the close (none since 2026-07-03T09:45:00Z)'
report "$label"

# (m) The same close, with a Z-form comment 30 seconds before it: inside the window.
label="Z-form comment 30 seconds before an offset-form close"
scenario offset-grace
cat > "$fixture_dir/issue-list.json" <<'EOF'
[{"number":1,"title":"Issue one","state":"CLOSED","closedAt":"2026-07-03T12:00:00+02:00"}]
EOF
cat > "$fixture_dir/comments-1.json" <<'EOF'
[{"user":{"login":"maintainer"},"created_at":"2026-07-03T09:59:30Z"}]
EOF
run_checker head-plain
expect_status "$label" 0
expect_line "$label" '#1 ok — Issue one (closed 2026-07-03T12:00:00+02:00)'
report "$label"

# (n) A listing that fills its limit is a truncated candidate set, not a short one.
label="candidate listing saturates its limit"
scenario saturated-listing
scenario_limit=3
cat > "$fixture_dir/issue-list.json" <<'EOF'
[{"number":1,"title":"Issue one","state":"CLOSED","closedAt":"2026-07-03T12:00:00Z"},
 {"number":2,"title":"Issue two","state":"CLOSED","closedAt":"2026-07-03T12:00:01Z"},
 {"number":3,"title":"Issue three","state":"CLOSED","closedAt":"2026-07-03T12:00:02Z"}]
EOF
run_checker head-plain
expect_status "$label" 1
expect_line "$label" 'error: candidate list saturated at 3 — raise the limit or narrow the window'
expect_no_line "$label" '#1 '
expect_call "$label" '--limit 3'
report "$label"

echo "check-issue-close-comments self-test: 15 scenarios passed."
