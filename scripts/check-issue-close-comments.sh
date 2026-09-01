#!/usr/bin/env bash
set -euo pipefail

# Check the issues this release delta is answerable for.
#
# The candidate set is the union of two sources, because neither alone sees the
# whole release: GitHub's own list of issues CLOSED between the previous release
# tag and the head ref, and every issue the delta's commit messages reference by
# number. The close ceremony closes issues by hand AFTER the tag exists, so the
# closes belonging to a release land outside its own tag-to-tag window and are
# only visible through the commit references; conversely an issue closed during
# the cycle need never be mentioned in a commit message at all.
#
# Open issues are noted without failing (they still need their close comment),
# issues closed before the window belong to an earlier release and are skipped,
# and issues closed from the window onwards fail unless the repository owner's
# explanation is part of the close: a comment any time AFTER the close, or within
# the fifteen minutes BEFORE it. The grace window is what the ceremony actually
# does — the explanation is written first and the issue closed by hand seconds
# later — where "strictly after the close" described the older trailer-close era,
# in which a merge closed the issue and the explanation could only follow.

# The close comment may precede the close by this much and still count as part of it.
grace_seconds=900

# How many closed issues one listing may return. A window that saturates it is not
# reported as a short list — it stops the gate (see the saturation check below).
issue_list_limit="${ISSUE_LIST_LIMIT:-500}"

usage() {
  echo "Usage: $(basename "$0") <prev-release-tag> [<head-ref>]" >&2
}

gh_call() {
  command gh "$@"
}

to_utc() {
  date -u -d "$1" +%Y-%m-%dT%H:%M:%S+00:00
}

to_epoch() {
  date -u -d "$1" +%s
}

# The end of the window is the moment the release was cut, not the moment its last
# commit was written: `git tag` runs after the commits, and issues closed in between
# belong to this release. Only a ref that IS a tag has such a moment. Every other
# ref — a branch, HEAD, a bare commit, even one that some tag already contains —
# runs to now: ending at the commit's own date would silently drop every issue
# closed after that commit and before this run, which is exactly the blind spot
# this gate exists to close.
window_end() {
  local ref="$1"
  if git rev-parse -q --verify "refs/tags/$ref" >/dev/null; then
    git for-each-ref --format='%(creatordate:iso8601-strict)' "refs/tags/$ref"
    return
  fi
  git rev-parse -q --verify "$ref^{commit}" >/dev/null
  date -u +%Y-%m-%dT%H:%M:%S+00:00
}

if (( $# < 1 || $# > 2 )); then
  usage
  exit 1
fi

prev_release_tag="$1"
head_ref="${2:-HEAD}"

gh_error_file="$(mktemp "${TMPDIR:-/tmp}/check-issue-close-comments.XXXXXX")"
trap 'rm -f "$gh_error_file"' EXIT

from_raw="$(git for-each-ref --format='%(creatordate:iso8601-strict)' "refs/tags/$prev_release_tag")"
if [[ -z "$from_raw" ]]; then
  echo "error: no such tag: $prev_release_tag" >&2
  exit 1
fi

from="$(to_utc "$from_raw")"
to="$(to_utc "$(window_end "$head_ref")")"
from_epoch="$(to_epoch "$from")"

echo "window $from..$to ($prev_release_tag..$head_ref)"

repo_json="$(gh_call repo view --json nameWithOwner,owner)"
repo_name="$(jq -er '.nameWithOwner' <<< "$repo_json")"
maintainer_login="$(jq -er '.owner.login' <<< "$repo_json")"

declare -a candidates=()
declare -A candidate_state=()
declare -A candidate_title=()
declare -A candidate_closed_at=()

remember_candidate() {
  local number="$1" title="$2" state="$3" closed_at="$4"
  [[ -n "${candidate_state[$number]+set}" ]] && return 0
  candidates+=("$number")
  candidate_state["$number"]="$state"
  candidate_title["$number"]="$title"
  candidate_closed_at["$number"]="$closed_at"
}

# Source A: everything GitHub says was closed inside the window.
closed_in_window_json="$(gh_call issue list --state closed \
  --search "closed:$from..$to" --limit "$issue_list_limit" --json number,title,state,closedAt)"
if (( $(jq -er 'length' <<< "$closed_in_window_json") >= issue_list_limit )); then
  echo "error: candidate list saturated at $issue_list_limit — raise the limit or narrow the window" >&2
  exit 1
fi
while IFS=$'\t' read -r number title state closed_at; do
  [[ -z "$number" ]] && continue
  remember_candidate "$number" "$title" "$state" "$closed_at"
done < <(jq -r '.[] | [(.number|tostring), .title, .state, (.closedAt // "")] | @tsv' \
  <<< "$closed_in_window_json")

# Source B: every issue the delta's commit messages name, trailer or prose.
commit_messages="$(git log --format=%B "$prev_release_tag..$head_ref")"
while read -r number; do
  [[ -z "$number" ]] && continue
  [[ -n "${candidate_state[$number]+set}" ]] && continue
  if ! issue_json="$(gh_call issue view "$number" --json title,state,closedAt 2>"$gh_error_file")"; then
    gh_error="$(cat "$gh_error_file")"
    # A number in prose that names no issue is not a ceremony violation. Anything
    # else — rate limit, auth, network — must stop the gate: dropping a candidate
    # on a transient error is the false green this gate exists to prevent.
    if grep -qiE 'could not resolve to an|HTTP 404' <<< "$gh_error"; then
      echo "#$number referenced in the delta but not a repository issue — ignored" >&2
      continue
    fi
    echo "error: gh issue view $number failed: $gh_error" >&2
    exit 1
  fi
  remember_candidate "$number" \
    "$(jq -r '.title' <<< "$issue_json")" \
    "$(jq -r '.state' <<< "$issue_json")" \
    "$(jq -r '.closedAt // ""' <<< "$issue_json")"
done < <(grep -oE '#[0-9]+' <<< "$commit_messages" | cut -c2- | sort -un)

if (( ${#candidates[@]} == 0 )); then
  echo "0 issues found in $from..$to (and none referenced in $prev_release_tag..$head_ref)"
  exit 0
fi

failed=0
ok_count=0
failing_count=0
pending_count=0
skipped_count=0

while read -r issue_number; do
  state="${candidate_state[$issue_number]}"
  title="${candidate_title[$issue_number]}"
  closed_at="${candidate_closed_at[$issue_number]}"

  if [[ "$state" != "CLOSED" ]]; then
    echo "#$issue_number pending close — will need a close comment"
    pending_count=$((pending_count + 1))
    continue
  fi

  if (( $(to_epoch "$closed_at") < from_epoch )); then
    echo "#$issue_number closed before the window (not this release's) — skipped"
    skipped_count=$((skipped_count + 1))
    continue
  fi

  # Timestamps arrive in whatever form each API returns them (Z here, an offset
  # there), so every comparison runs on epoch seconds, never on the strings.
  grace_epoch=$(( $(to_epoch "$closed_at") - grace_seconds ))
  grace_start="$(date -u -d "@$grace_epoch" +%Y-%m-%dT%H:%M:%SZ)"
  comments_json="$(gh_call api --paginate "repos/$repo_name/issues/$issue_number/comments")"

  has_close_comment=0
  while read -r comment_created_at; do
    [[ -z "$comment_created_at" ]] && continue
    if (( $(to_epoch "$comment_created_at") >= grace_epoch )); then
      has_close_comment=1
      break
    fi
  done < <(jq -sr --arg maintainer "$maintainer_login" \
    '.[][] | select(.user.login == $maintainer) | .created_at' <<< "$comments_json")

  if (( has_close_comment == 1 )); then
    echo "#$issue_number ok — $title (closed $closed_at)"
    ok_count=$((ok_count + 1))
  else
    echo "#$issue_number FAIL — $title — closed $closed_at, no maintainer comment as part of the close (none since $grace_start)" >&2
    failing_count=$((failing_count + 1))
    failed=1
  fi
done < <(printf '%s\n' "${candidates[@]}" | sort -un)

echo "checked ${#candidates[@]} issues: $ok_count ok, $failing_count failing, $pending_count pending close, $skipped_count skipped (pre-window)"

exit "$failed"
