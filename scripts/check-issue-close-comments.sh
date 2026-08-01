#!/usr/bin/env bash
set -euo pipefail

# Check issues referenced by Fixes/Closes/Resolves trailers in a release delta.
# Open issues are noted without failing; closed issues fail unless the repository
# owner commented strictly after the issue's close time.

usage() {
  echo "Usage: $(basename "$0") <prev-release-tag> [<head-ref>]" >&2
}

gh_call() {
  command gh "$@"
}

if (( $# < 1 || $# > 2 )); then
  usage
  exit 1
fi

prev_release_tag="$1"
head_ref="${2:-HEAD}"

commit_messages="$(git log --format=%B "$prev_release_tag..$head_ref")"

declare -a issue_numbers=()
declare -A seen_issues=()
shopt -s nocasematch
while IFS= read -r line; do
  if [[ "$line" =~ ^[[:space:]]*(Fixes|Closes|Resolves)[[:space:]]+\#([0-9]+)[[:space:]]*$ ]]; then
    issue_number="${BASH_REMATCH[2]}"
    if [[ -z "${seen_issues[$issue_number]+set}" ]]; then
      issue_numbers+=("$issue_number")
      seen_issues["$issue_number"]=1
    fi
  fi
done <<< "$commit_messages"
shopt -u nocasematch

if (( ${#issue_numbers[@]} == 0 )); then
  exit 0
fi

repo_json="$(gh_call repo view --json nameWithOwner,owner)"
repo_name="$(jq -er '.nameWithOwner' <<< "$repo_json")"
maintainer_login="$(jq -er '.owner.login' <<< "$repo_json")"

failed=0
for issue_number in "${issue_numbers[@]}"; do
  issue_json="$(gh_call issue view "$issue_number" --json title,state,closedAt)"
  state="$(jq -er '.state' <<< "$issue_json")"

  if [[ "$state" == "OPEN" ]]; then
    echo "#$issue_number pending close — will need a close comment"
    continue
  fi

  title="$(jq -er '.title' <<< "$issue_json")"
  closed_at="$(jq -er '.closedAt' <<< "$issue_json")"
  comments_json="$(gh_call api --paginate "repos/$repo_name/issues/$issue_number/comments")"

  if ! jq -se --arg maintainer "$maintainer_login" --arg closed_at "$closed_at" \
    'any(.[][]; .user.login == $maintainer and .created_at > $closed_at)' \
    >/dev/null <<< "$comments_json"; then
    echo "#$issue_number $title — closed $closed_at, no maintainer comment after close" >&2
    failed=1
  fi
done

exit "$failed"
