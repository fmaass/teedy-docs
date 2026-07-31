#!/usr/bin/env bash
set -euo pipefail

# Two Jetty pins exist and neither one implies the other:
#
#   pom.xml  <org.eclipse.jetty.version>   -> the EMBEDDED Jetty (jetty-ee10-maven-plugin
#                                             for `mvn jetty:run`, jetty-server/-ee10-webapp
#                                             /-ee10-servlet on the test classpath)
#   Dockerfile ENV JETTY_VERSION           -> the jetty-home distribution downloaded into
#                                             the image, i.e. the Jetty that actually SERVES
#                                             production
#
# Bumping only the pom leaves production on the old Jetty while the tree reads as upgraded,
# so a Jetty CVE fix can look applied and be entirely inert. Nothing else catches this: no
# unit test loads the Dockerfile, and the image boots fine on either version.

repo_root="${1:-$(pwd)}"

pom_file="$repo_root/pom.xml"
dockerfile="$repo_root/Dockerfile"

for f in "$pom_file" "$dockerfile"; do
  if [[ ! -f "$f" ]]; then
    echo "Missing ${f#"$repo_root"/}; cannot compare the Jetty pins." >&2
    exit 1
  fi
done

# Both sides take the EFFECTIVE pin, not the first text match, and ignore commented-out
# lines. A stale `# ENV JETTY_VERSION=<old>` or an XML-commented property sitting above the
# real one would otherwise be read as the pin and let genuine drift pass silently.

# XML comments can span lines, so strip every <!-- ... --> region before matching.
strip_xml_comments() {
  awk '
    {
      line = $0
      while (1) {
        if (incomment) {
          p = index(line, "-->")
          if (p == 0) { line = ""; break }
          line = substr(line, p + 3); incomment = 0
        } else {
          p = index(line, "<!--")
          if (p == 0) break
          rest = substr(line, p + 4)
          q = index(rest, "-->")
          if (q == 0) { line = substr(line, 1, p - 1); incomment = 1; break }
          line = substr(line, 1, p - 1) substr(rest, q + 3)
        }
      }
      print line
    }
  ' "$1"
}

# Maven: a property redefined in the same <properties> block resolves to the LAST one.
# The element is matched on the comment-stripped, whitespace-joined stream so a value an
# IDE reformat split across lines is still seen; tr flattens newlines before the match.
pom_version="$(strip_xml_comments "$pom_file" \
  | tr '\n' ' ' \
  | grep -o '<org\.eclipse\.jetty\.version>[^<]*</org\.eclipse\.jetty\.version>' \
  | sed 's|<org\.eclipse\.jetty\.version>\([^<]*\)</org\.eclipse\.jetty\.version>|\1|' \
  | tail -n 1)"
pom_version="${pom_version//[[:space:]]/}"

# Docker: instructions may be preceded by whitespace, so match an optionally-indented `ENV`
# instruction), and when the same variable is set twice the LAST assignment is effective.
docker_version="$(sed -n 's|^[[:space:]]*ENV[[:space:]]\{1,\}JETTY_VERSION=\(.*\)$|\1|p' "$dockerfile" | tail -n 1)"
docker_version="${docker_version//[[:space:]]/}"
docker_version="${docker_version%\"}"
docker_version="${docker_version#\"}"

if [[ -z "$pom_version" ]]; then
  echo "pom.xml has no <org.eclipse.jetty.version> property." >&2
  exit 1
fi

if [[ -z "$docker_version" ]]; then
  echo "Dockerfile has no 'ENV JETTY_VERSION=' line." >&2
  exit 1
fi

if [[ "$pom_version" != "$docker_version" ]]; then
  echo "Jetty pin drift: pom.xml <org.eclipse.jetty.version> is $pom_version but Dockerfile ENV JETTY_VERSION is $docker_version." >&2
  echo "Set both to the same version. If you are moving the Dockerfile pin, also refresh ENV JETTY_SHA512 to the published jetty-home-<version>.tar.gz.sha512 or the image build will fail its checksum check." >&2
  exit 1
fi

echo "Jetty pins agree: pom.xml and Dockerfile are both $pom_version."
