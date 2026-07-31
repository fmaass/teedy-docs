#!/usr/bin/env bash
set -euo pipefail

# Companion test for check-jetty-version.sh.
#
# Every case runs against a synthetic repo root under a temp dir, so the real pom.xml and
# Dockerfile are never touched. Beyond the plain agree/disagree base cases, the MASKING cases
# are the point: a commented-out or duplicated pin must never be mistaken for the effective
# one. A first-match parse reads the stale value on both sides at once and reports "pins
# agree" while production is genuinely on the older Jetty -- the exact false pass this
# checker exists to prevent.

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
checker="$script_dir/check-jetty-version.sh"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/check-jetty-version.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

# --- fixture builders -------------------------------------------------------------------

pom_plain() {
  cat <<EOF
<project>
  <properties>
    <org.eclipse.jetty.version>$1</org.eclipse.jetty.version>
  </properties>
</project>
EOF
}

# A single-line XML comment holding an OLD pin, sitting above the real one.
pom_masked() {
  cat <<EOF
<project>
  <properties>
    <!-- was <org.eclipse.jetty.version>$1</org.eclipse.jetty.version> -->
    <org.eclipse.jetty.version>$2</org.eclipse.jetty.version>
  </properties>
</project>
EOF
}

# Same, but the comment spans lines -- a line-at-a-time filter would miss the closing tag.
pom_masked_multiline() {
  cat <<EOF
<project>
  <properties>
    <!--
      pinned before the upgrade:
      <org.eclipse.jetty.version>$1</org.eclipse.jetty.version>
    -->
    <org.eclipse.jetty.version>$2</org.eclipse.jetty.version>
  </properties>
</project>
EOF
}

dock_plain() {
  cat <<EOF
FROM ubuntu:24.04
ENV JETTY_VERSION=$1
RUN echo build
EOF
}

# A commented-out pin above the effective instruction.
dock_commented() {
  cat <<EOF
FROM ubuntu:24.04
# ENV JETTY_VERSION=$1
ENV JETTY_VERSION=$2
RUN echo build
EOF
}

# Two effective ENV assignments: Docker's LAST one wins.
dock_duplicate() {
  cat <<EOF
FROM ubuntu:24.04
ENV JETTY_VERSION=$1
ENV JETTY_VERSION=$2
RUN echo build
EOF
}

# Docker permits leading whitespace before an instruction; the LAST (indented)
# assignment is still the effective one and must not be skipped by the parser.
dock_indented_last() {
  cat <<EOF
FROM ubuntu:24.04
ENV JETTY_VERSION=$1
    ENV JETTY_VERSION=$2
RUN echo build
EOF
}

make_fixture() {
  local root="$tmp_dir/$1"
  mkdir -p "$root"
  printf '%s' "$2" > "$root/pom.xml"
  printf '%s' "$3" > "$root/Dockerfile"
  printf '%s' "$root"
}

# --- assertion --------------------------------------------------------------------------

failures=0
cases=0

expect() {
  local name="$1" root="$2" want="$3"   # want: pass | fail
  cases=$((cases + 1))
  local out="$root/checker.out"
  local rc=0
  bash "$checker" "$root" >"$out" 2>&1 || rc=$?

  local got="pass"
  [ "$rc" -ne 0 ] && got="fail"

  if [ "$got" = "$want" ]; then
    printf 'ok   %-58s (want %s, exit %d)\n' "$name" "$want" "$rc"
    sed 's/^/       /' "$out"
  else
    printf 'FAIL %-58s (want %s, got %s, exit %d)\n' "$name" "$want" "$got" "$rc" >&2
    sed 's/^/       /' "$out" >&2
    failures=$((failures + 1))
  fi
}

# --- cases ------------------------------------------------------------------------------

# Base: the two pins simply agree / simply differ.
expect "base: pins agree" \
  "$(make_fixture base-agree "$(pom_plain 12.0.37)" "$(dock_plain 12.0.37)")" pass

expect "base: pins differ" \
  "$(make_fixture base-differ "$(pom_plain 12.0.37)" "$(dock_plain 12.0.36)")" fail

# P1: the false pass. The pom's real pin is 12.0.37 but an XML-commented 12.0.36 sits above
# it, and the Dockerfile is genuinely stale at 12.0.36 -- a first-match parse reads 12.0.36
# on BOTH sides, calls them equal, and waves real drift through.
expect "P1 masked pom + genuinely stale Dockerfile" \
  "$(make_fixture p1 "$(pom_masked 12.0.36 12.0.37)" "$(dock_plain 12.0.36)")" fail

# P2: same mask, but the pins genuinely agree -- must not false-fail.
expect "P2 masked pom, pins agree" \
  "$(make_fixture p2 "$(pom_masked 12.0.36 12.0.37)" "$(dock_plain 12.0.37)")" pass

# P3: a commented-out ENV above the real one, pins agree.
expect "P3 commented-out ENV, pins agree" \
  "$(make_fixture p3 "$(pom_plain 12.0.37)" "$(dock_commented 12.0.36 12.0.37)")" pass

# P4: the comment shows the NEW version while the effective ENV is still stale.
expect "P4 commented-out ENV hiding a stale effective pin" \
  "$(make_fixture p4 "$(pom_plain 12.0.37)" "$(dock_commented 12.0.37 12.0.36)")" fail

# P5: duplicate effective ENV -- Docker uses the LAST, so these pins do agree.
expect "P5 duplicate ENV, last assignment wins" \
  "$(make_fixture p5 "$(pom_plain 12.0.37)" "$(dock_duplicate 12.0.36 12.0.37)")" pass

# P8: a later INDENTED ENV is the effective assignment; an anchored-only parse
# would read the earlier pom-matching pin and mask real drift.
expect "P8 indented effective ENV masks drift" \
  "$(make_fixture p8i "$(pom_plain 12.0.37)" "$(dock_indented_last 12.0.37 12.0.36)")" fail

expect "P8b indented effective ENV, pins agree" \
  "$(make_fixture p8b "$(pom_plain 12.0.36)" "$(dock_indented_last 12.0.37 12.0.36)")" pass

# P9: an IDE reformat can split the final property across lines; the earlier
# single-line definition must not mask it.
pom_multiline_last() {
  cat <<EOF
<project>
  <properties>
    <org.eclipse.jetty.version>$1</org.eclipse.jetty.version>
    <org.eclipse.jetty.version>
      $2
    </org.eclipse.jetty.version>
  </properties>
</project>
EOF
}

expect "P9 multi-line final pom property masks drift" \
  "$(make_fixture p9m "$(pom_multiline_last 12.0.36 12.0.37)" "$(dock_plain 12.0.36)")" fail

expect "P9b multi-line final pom property, pins agree" \
  "$(make_fixture p9b "$(pom_multiline_last 12.0.36 12.0.37)" "$(dock_plain 12.0.37)")" pass

# P6: the mask spans multiple lines.
expect "P6 multi-line XML comment mask, pins agree" \
  "$(make_fixture p6 "$(pom_masked_multiline 12.0.36 12.0.37)" "$(dock_plain 12.0.37)")" pass

expect "P7 multi-line XML comment mask + stale Dockerfile" \
  "$(make_fixture p7 "$(pom_masked_multiline 12.0.36 12.0.37)" "$(dock_plain 12.0.36)")" fail

# --- verdict ----------------------------------------------------------------------------

printf '\n'
if [ "$failures" -ne 0 ]; then
  printf '*** check-jetty-version self-test FAILED: %d case(s) behaved wrongly.\n' "$failures" >&2
  exit 1
fi

printf 'check-jetty-version self-test passed: %d cases, masking, duplicate and indented pins all resolved to the effective value.\n' "$cases"
