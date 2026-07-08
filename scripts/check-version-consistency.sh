#!/usr/bin/env bash
# Assert that the release tag, the Maven project version, and the frontend
# package.json version all agree. Intended to run in CI on tag builds so a
# release tag can never ship an image whose embedded versions disagree.
#
# Usage:
#   check-version-consistency.sh <tag> [repo_root]
#
# <tag> is the git tag being built (e.g. "v3.0.0" or "3.0.0"); a leading "v"
# is stripped before comparison. If <tag> is empty the tag check is skipped
# and the script only asserts that pom.xml and package.json agree.
set -euo pipefail

raw_tag="${1:-}"
repo_root="${2:-$(pwd)}"

pom_file="$repo_root/pom.xml"
package_file="$repo_root/docs-web/src/main/webapp/package.json"

if [[ ! -f "$pom_file" ]]; then
  echo "Missing pom.xml: $pom_file" >&2
  exit 1
fi
if [[ ! -f "$package_file" ]]; then
  echo "Missing package.json: $package_file" >&2
  exit 1
fi

# Root <version> is the first top-level <version> element in the parent pom.
pom_version="$(grep -m1 -oE '<version>[^<]+</version>' "$pom_file" | sed -E 's/<\/?version>//g')"
if [[ -z "$pom_version" ]]; then
  echo "Could not read <version> from $pom_file" >&2
  exit 1
fi

package_version="$(grep -m1 -oE '"version"[[:space:]]*:[[:space:]]*"[^"]+"' "$package_file" \
  | sed -E 's/.*"version"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')"
if [[ -z "$package_version" ]]; then
  echo "Could not read \"version\" from $package_file" >&2
  exit 1
fi

status=0

if [[ "$pom_version" != "$package_version" ]]; then
  echo "Version mismatch: pom.xml=$pom_version package.json=$package_version" >&2
  status=1
fi

if [[ -n "$raw_tag" ]]; then
  tag_version="${raw_tag#v}"
  if [[ "$tag_version" != "$pom_version" ]]; then
    echo "Version mismatch: tag=$raw_tag (=$tag_version) pom.xml=$pom_version" >&2
    status=1
  fi
  if [[ "$tag_version" != "$package_version" ]]; then
    echo "Version mismatch: tag=$raw_tag (=$tag_version) package.json=$package_version" >&2
    status=1
  fi
fi

if [[ "$status" -ne 0 ]]; then
  echo "Version consistency check FAILED." >&2
  exit 1
fi

if [[ -n "$raw_tag" ]]; then
  echo "Version consistency OK: tag=$raw_tag pom=$pom_version package=$package_version"
else
  echo "Version consistency OK: pom=$pom_version package=$package_version (no tag)"
fi
