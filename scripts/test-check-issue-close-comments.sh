#!/usr/bin/env bash
set -euo pipefail

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

printf 'base\n' > "$repo/history.txt"
git -C "$repo" add history.txt
git -C "$repo" commit -q -m "base"
git -C "$repo" tag prev-release

printf 'single\n' >> "$repo/history.txt"
git -C "$repo" commit -q -am $'single issue\n\nFixes #1'
single_head="$(git -C "$repo" rev-parse HEAD)"
git -C "$repo" tag no-trailers-prev

printf 'plain\n' >> "$repo/history.txt"
git -C "$repo" commit -q -am "no issue trailer"
no_trailers_head="$(git -C "$repo" rev-parse HEAD)"

printf 'multi\n' >> "$repo/history.txt"
git -C "$repo" commit -q -am $'two issues\n\nFixes #1\nFixes #2'
multi_head="$(git -C "$repo" rev-parse HEAD)"

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

if [[ "${1:-}" == "issue" && "${2:-}" == "view" ]]; then
  if (( $# != 5 )) || [[ "$4" != "--json" || "$5" != "title,state,closedAt" ]]; then
    for arg in "${@:3}"; do
      [[ "$arg" == -* ]] && unknown_flag "$arg"
    done
    echo "Unexpected gh invocation: $*" >&2
    exit 2
  fi
  issue_number="$3"
  if [[ "$GH_FIXTURE_MODE" == "open" ]]; then
    printf '{"title":"Issue %s","state":"OPEN","closedAt":null}\n' "$issue_number"
  else
    printf '{"title":"Issue %s","state":"CLOSED","closedAt":"2026-07-20T12:00:00Z"}\n' "$issue_number"
  fi
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
  case "$GH_FIXTURE_MODE" in
    after)
      printf '%s\n' '[{"user":{"login":"contributor"},"created_at":"2026-07-20T12:00:01Z"}]'
      printf '%s\n' '[{"user":{"login":"maintainer"},"created_at":"2026-07-20T12:00:01Z"}]'
      ;;
    multi)
      printf '%s\n' '[{"user":{"login":"maintainer"},"created_at":"2026-07-20T12:00:01Z"}]'
      ;;
    before)
      printf '%s\n' '[{"user":{"login":"maintainer"},"created_at":"2026-07-20T11:59:59Z"}]'
      ;;
    other)
      printf '%s\n' '[{"user":{"login":"contributor"},"created_at":"2026-07-20T12:00:01Z"}]'
      ;;
    *)
      echo "Unexpected fixture mode for comments: $GH_FIXTURE_MODE" >&2
      exit 1
      ;;
  esac
  exit 0
fi

echo "Unexpected gh invocation: $*" >&2
exit 2
EOF
chmod +x "$shim_dir/gh"

export PATH="$shim_dir:$PATH"
export GH_CALL_LOG="$tmp_dir/gh-calls.log"

set +e
GH_FIXTURE_MODE=after "$shim_dir/gh" api --paginate --slurp \
  repos/maintainer/project/issues/1/comments >/dev/null 2>&1
shim_status=$?
set -e
if (( shim_status != 2 )); then
  echo "FAIL: gh shim did not reject an unknown flag with exit 2" >&2
  exit 1
fi

run_green() {
  local label="$1"
  local mode="$2"
  local previous="$3"
  local head="$4"
  local output_file="$tmp_dir/$mode-green.out"

  : > "$GH_CALL_LOG"
  if ! (cd "$repo" && GH_FIXTURE_MODE="$mode" bash "$checker" "$previous" "$head") >"$output_file" 2>&1; then
    echo "FAIL: $label was rejected" >&2
    cat "$output_file" >&2
    exit 1
  fi

  echo "$label correctly accepted:"
  cat "$output_file"
}

run_red() {
  local label="$1"
  local mode="$2"
  local expected="$3"
  local output_file="$tmp_dir/$mode-red.out"

  : > "$GH_CALL_LOG"
  if (cd "$repo" && GH_FIXTURE_MODE="$mode" bash "$checker" prev-release "$single_head") >"$output_file" 2>&1; then
    echo "FAIL: $label was accepted" >&2
    cat "$output_file" >&2
    exit 1
  fi
  if ! grep -Fq "$expected" "$output_file"; then
    echo "FAIL: $label did not emit the expected violation" >&2
    cat "$output_file" >&2
    exit 1
  fi

  echo "$label correctly rejected:"
  cat "$output_file"
}

run_green "AFTER-close maintainer comment fixture" after prev-release "$single_head"

violation='#1 Issue 1 — closed 2026-07-20T12:00:00Z, no maintainer comment after close'
run_red "BEFORE-close maintainer comment fixture" before "$violation"
run_red "NON-maintainer comment fixture" other "$violation"

run_green "OPEN issue fixture" open prev-release "$single_head"
if ! grep -Fq '#1 pending close — will need a close comment' "$tmp_dir/open-green.out"; then
  echo "FAIL: open issue did not emit the pending-close note" >&2
  cat "$tmp_dir/open-green.out" >&2
  exit 1
fi

run_green "MULTI-trailer fixture" multi prev-release "$multi_head"
if ! grep -Fq 'issue view 1' "$GH_CALL_LOG" || ! grep -Fq 'issue view 2' "$GH_CALL_LOG"; then
  echo "FAIL: multi-trailer delta did not check both issues" >&2
  cat "$GH_CALL_LOG" >&2
  exit 1
fi

run_green "NO-trailer fixture" unused no-trailers-prev "$no_trailers_head"
if [[ -s "$GH_CALL_LOG" ]]; then
  echo "FAIL: no-trailer delta unexpectedly called gh" >&2
  cat "$GH_CALL_LOG" >&2
  exit 1
fi
