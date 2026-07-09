#!/usr/bin/env bash
# Runtime smoke test for the Teedy Docker image.
#
# Boots the given image standalone (no DATABASE_URL -> embedded H2, the app's
# built-in fallback) and asserts that a real, unauthenticated API route serves.
# GET /api/user returns HTTP 200 with {"anonymous":true} when the app is up, so
# it doubles as the liveness probe the production HEALTHCHECK uses.
#
# Exits non-zero (failing CI) if the container never serves within the timeout,
# catching a green build whose image cannot actually start or serve.
#
# Usage: docker-smoke-test.sh <image-ref>
set -euo pipefail

image="${1:?usage: docker-smoke-test.sh <image-ref>}"
container="teedy-smoke-$$"
host_port="${SMOKE_PORT:-18080}"
route="/api/user"
max_wait="${SMOKE_TIMEOUT:-120}"   # seconds to wait for the app to serve

cleanup() {
  echo "::group::container logs (${container})"
  docker logs "${container}" 2>&1 | tail -n 200 || true
  echo "::endgroup::"
  docker rm -f "${container}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Starting container from ${image} (embedded H2, port ${host_port})..."
# No DATABASE_URL: EMF.java falls back to jdbc:h2:file under docs.home (/data).
docker run -d --name "${container}" -p "${host_port}:8080" "${image}" >/dev/null

url="http://localhost:${host_port}${route}"
echo "Waiting up to ${max_wait}s for ${url} to return HTTP 200..."

deadline=$(( $(date +%s) + max_wait ))
while true; do
  # Fail fast if the container died during startup.
  if [ "$(docker inspect -f '{{.State.Running}}' "${container}" 2>/dev/null || echo false)" != "true" ]; then
    echo "FAIL: container exited before serving ${route}."
    exit 1
  fi

  code="$(curl -s -o /tmp/smoke-body.txt -w '%{http_code}' "${url}" || echo 000)"
  if [ "${code}" = "200" ]; then
    echo "OK: ${route} returned HTTP 200."
    echo "Body: $(cat /tmp/smoke-body.txt)"
    # Sanity-check the payload is the expected anonymous-session JSON.
    if grep -q '"anonymous":true' /tmp/smoke-body.txt; then
      echo "PASS: anonymous session confirmed."
      exit 0
    fi
    echo "FAIL: 200 but unexpected body (not an anonymous session)."
    exit 1
  fi

  if [ "$(date +%s)" -ge "${deadline}" ]; then
    echo "FAIL: ${route} did not return 200 within ${max_wait}s (last code: ${code})."
    exit 1
  fi

  sleep 3
done
