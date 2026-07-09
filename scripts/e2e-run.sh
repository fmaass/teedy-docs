#!/usr/bin/env bash
# End-to-end test harness for Teedy.
#
# Boots the production Docker image standalone (no DATABASE_URL -> embedded H2,
# default admin/admin) on port 8080 at context path "/", waits for /api/user to
# serve, runs the Playwright suite against it, and tears the container down. This
# is the single entrypoint both CI and a local developer call.
#
# The Playwright login uses Teedy's NATIVE form login (admin/admin) — NOT Authelia
# (Authelia only fronts production).
#
# Usage:
#   scripts/e2e-run.sh                 # build the image, then run e2e
#   E2E_IMAGE=teedy:local scripts/e2e-run.sh   # reuse an already-built image
#
# Env:
#   E2E_IMAGE     image ref to boot (default: build ./ as teedy-e2e:local)
#   E2E_PORT      host port to expose 8080 on (default 8080)
#   E2E_TIMEOUT   seconds to wait for /api/user readiness (default 180)
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
webapp="${repo_root}/docs-web/src/main/webapp"

image="${E2E_IMAGE:-}"
host_port="${E2E_PORT:-8080}"
max_wait="${E2E_TIMEOUT:-180}"
container="teedy-e2e-$$"

cleanup() {
  echo "::group::container logs (${container})"
  docker logs "${container}" 2>&1 | tail -n 200 || true
  echo "::endgroup::"
  docker rm -f "${container}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ -z "${image}" ]; then
  image="teedy-e2e:local"
  # The Dockerfile COPYies a pre-built prod WAR (docs-web/target/docs-web-*.war)
  # rather than building inside the image — build it first unless it already exists
  # (CI's `build` job produces it; a local run builds it here).
  if ! ls "${repo_root}"/docs-web/target/docs-web-*.war >/dev/null 2>&1; then
    echo "Building production WAR (./mvnw -Pprod -DskipTests clean install)..."
    "${repo_root}/mvnw" -f "${repo_root}/pom.xml" -Pprod -DskipTests clean install
  fi
  echo "Building Docker image ${image}..."
  docker build -t "${image}" "${repo_root}"
fi

echo "Starting container from ${image} (embedded H2, port ${host_port})..."
docker run -d --name "${container}" -p "${host_port}:8080" "${image}" >/dev/null

url="http://localhost:${host_port}/api/user"
echo "Waiting up to ${max_wait}s for ${url} to return HTTP 200..."
deadline=$(( $(date +%s) + max_wait ))
while true; do
  if [ "$(docker inspect -f '{{.State.Running}}' "${container}" 2>/dev/null || echo false)" != "true" ]; then
    echo "FAIL: container exited before serving /api/user."
    exit 1
  fi
  code="$(curl -s -o /dev/null -w '%{http_code}' "${url}" || echo 000)"
  if [ "${code}" = "200" ]; then
    echo "OK: /api/user returned HTTP 200 — app is up."
    break
  fi
  if [ "$(date +%s)" -ge "${deadline}" ]; then
    echo "FAIL: /api/user did not return 200 within ${max_wait}s (last code: ${code})."
    exit 1
  fi
  sleep 3
done

export PLAYWRIGHT_BASE_URL="http://localhost:${host_port}"
echo "Running Playwright e2e suite against ${PLAYWRIGHT_BASE_URL}..."
cd "${webapp}"
npx playwright test "$@"
