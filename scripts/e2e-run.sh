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
# Two run modes (OS-consistent visual baselines — see below):
#   DEFAULT (functional):  runs the whole suite on the HOST via `npx playwright test`,
#                          but EXCLUDES the pixel-comparison visual specs (`--grep-invert
#                          @visual`). The host runner (GitHub ubuntu-latest = Noble) has
#                          different system fonts than the jammy container the visual
#                          baselines were generated in, so a host pixel-diff would flake.
#                          The deterministic FUNCTIONAL specs (incl. the German-overflow
#                          checks) still run here at both viewports.
#   VISUAL-ONLY (E2E_VISUAL_ONLY=1): runs ONLY the `@visual` specs INSIDE the
#                          `mcr.microsoft.com/playwright:v1.<ver>-jammy` container joined to
#                          the app container's network namespace (so the app is reachable
#                          as http://localhost:8080 — Teedy's session cookie only sticks on
#                          a `localhost` origin). This is the EXACT environment the
#                          committed `*-linux.png` baselines match, so the diff is a real,
#                          reliable gate. The Playwright image tag is pinned to the repo's
#                          @playwright/test version (from package-lock.json).
#
# Usage:
#   scripts/e2e-run.sh                          # build image, run FUNCTIONAL suite on host
#   E2E_IMAGE=teedy:local scripts/e2e-run.sh    # reuse an already-built image
#   E2E_VISUAL_ONLY=1 E2E_IMAGE=teedy:local scripts/e2e-run.sh   # visual gate in jammy container
#
# Env:
#   E2E_IMAGE     image ref to boot (default: build ./ as teedy-e2e:local)
#   E2E_PORT      host port to expose 8080 on (default 8080)
#   E2E_TIMEOUT   seconds to wait for /api/user readiness (default 180)
#   E2E_ALLOW_STALE_WAR   set to 1 to skip the reused-WAR/pom version match check
#   E2E_VISUAL_ONLY       set to 1 to run ONLY the @visual pixel specs in the jammy container
#   E2E_CPUSET      value for the app container's `--cpuset-cpus` (e.g. "0,1"). Pin the
#                   Playwright runner to the SAME cores with `taskset -c 0,1 scripts/e2e-run.sh`
#                   to reproduce a contended, low-core environment (#186 flake repro).
#   E2E_DOCKER_ARGS extra, word-split arguments spliced into the app container's `docker run`
#                   (e.g. "--memory=2g --cpus=2"). Applies to the app container only.
#   E2E_ARTIFACT_DIR  where the run's diagnostics are written (default <repo>/e2e-artifacts,
#                   which is git-ignored): the app container's FULL server log, and the
#                   admin-baseline drift journal the e2e fixtures append to.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
webapp="${repo_root}/docs-web/src/main/webapp"

image="${E2E_IMAGE:-}"
host_port="${E2E_PORT:-8080}"
max_wait="${E2E_TIMEOUT:-180}"
container="teedy-e2e-$$"

# Diagnostics survive the run (#186). The old teardown printed `docker logs | tail -n 200`
# and then removed the container, so the SERVER side of any failure — the stack trace, the
# 429, the request that never arrived — was destroyed seconds after the report was written.
# The full log is now written to a file first; the console still gets the same 200-line tail.
artifact_dir="${E2E_ARTIFACT_DIR:-${repo_root}/e2e-artifacts}"
run_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
server_log="${artifact_dir}/server-${run_stamp}-${container}.log"
mkdir -p "${artifact_dir}"
# Read by e2e/fixtures.ts so the fixture-level diagnostics land beside the server log.
export E2E_DIAG_DIR="${artifact_dir}"

cleanup() {
  docker logs "${container}" >"${server_log}" 2>&1 || true
  echo "::group::container logs (${container}) — last 200 of $(wc -l <"${server_log}" 2>/dev/null || echo 0) lines"
  tail -n 200 "${server_log}" 2>/dev/null || true
  echo "::endgroup::"
  echo "Full server log preserved at ${server_log}"
  docker rm -f "${container}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ -z "${image}" ]; then
  image="teedy-e2e:local"
  # The Dockerfile COPYies a pre-built prod WAR (docs-web/target/docs-web-*.war)
  # rather than building inside the image — build it first unless it already exists
  # (CI's `build` job produces it; a local run builds it here).
  war_glob=("${repo_root}"/docs-web/target/docs-web-*.war)
  if [ ! -e "${war_glob[0]}" ]; then
    echo "Building production WAR (./mvnw -Pprod -DskipTests clean install)..."
    "${repo_root}/mvnw" -f "${repo_root}/pom.xml" -Pprod -DskipTests clean install
  else
    # A REUSED WAR must match the current pom version — a stale WAR (e.g. a v3.3.0
    # artifact left in target while the tree is v3.4.0) silently boots the OLD app
    # under the CURRENT specs and produces bogus failures (2026-07-11). Fail loudly;
    # E2E_ALLOW_STALE_WAR=1 overrides for a deliberate cross-version run.
    war_file="$(basename "${war_glob[0]}")"
    war_version="${war_file#docs-web-}"
    war_version="${war_version%.war}"
    # Parent pom carries the authoritative version (literal <version> under docs-parent).
    pom_version="$(sed -n '/<artifactId>docs-parent<\/artifactId>/,/<\/version>/{s/.*<version>\(.*\)<\/version>.*/\1/p;}' "${repo_root}/pom.xml" | head -n1)"
    if [ -z "${pom_version}" ]; then
      echo "FAIL: could not read the project version from ${repo_root}/pom.xml." >&2
      exit 1
    fi
    if [ "${war_version}" != "${pom_version}" ]; then
      if [ "${E2E_ALLOW_STALE_WAR:-}" = "1" ]; then
        echo "WARNING: reusing WAR ${war_file} (v${war_version}) against pom v${pom_version} — E2E_ALLOW_STALE_WAR=1 set." >&2
      else
        echo "FAIL: reused WAR ${war_file} is v${war_version} but the tree is v${pom_version}." >&2
        echo "       A stale WAR runs the OLD app under the CURRENT specs (bogus failures)." >&2
        echo "       Rebuild it (./mvnw -Pprod -DskipTests clean install) or set E2E_ALLOW_STALE_WAR=1." >&2
        exit 1
      fi
    fi
  fi
  echo "Building Docker image ${image}..."
  docker build -t "${image}" "${repo_root}"
fi

# --add-host maps host.docker.internal -> the host gateway so a container can reach a
#   listener the test process runs on the host (webhook-delivery.spec.ts). It is a
#   built-in alias on Docker Desktop (macOS/Windows) and harmless there; on Linux/CI it
#   is what makes the alias resolve at all.
# DOCS_WEBHOOK_ALLOW_PRIVATE lets that same test register a webhook whose URL points at
#   the (private) host gateway — the SSRF guard blocks private targets by default.
#
# E2E_CPUSET / E2E_DOCKER_ARGS splice extra flags into this one `docker run` so a run can be
# constrained without editing the harness — the 2-core pin used to reproduce the #186 flake
# cluster (`E2E_CPUSET=0,1` here plus `taskset -c 0,1` on the runner) needs no other hook.
run_args=()
if [ -n "${E2E_CPUSET:-}" ]; then
  run_args+=(--cpuset-cpus="${E2E_CPUSET}")
fi
if [ -n "${E2E_DOCKER_ARGS:-}" ]; then
  # Deliberate word splitting: the variable carries several flags.
  # shellcheck disable=SC2206
  run_args+=(${E2E_DOCKER_ARGS})
fi

extra_desc=""
if [ ${#run_args[@]} -gt 0 ]; then
  extra_desc=" (extra docker args: ${run_args[*]})"
fi
echo "Starting container from ${image} (embedded H2, port ${host_port})${extra_desc}..."
docker run -d --name "${container}" \
  --add-host=host.docker.internal:host-gateway \
  -e DOCS_WEBHOOK_ALLOW_PRIVATE=true \
  ${run_args[@]+"${run_args[@]}"} \
  -p "${host_port}:8080" "${image}" >/dev/null

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

if [ "${E2E_VISUAL_ONLY:-}" = "1" ]; then
  # VISUAL GATE: run ONLY the @visual pixel specs INSIDE the pinned Playwright jammy
  # container, joined to the app container's network namespace so the app is reachable
  # as http://localhost:8080 (Teedy's session cookie only sticks on a `localhost`
  # origin — a non-localhost host makes the post-login /api/user come back anonymous
  # and the form login never completes). This is the exact OS/font environment the
  # committed *-linux.png baselines were generated in, so the diff is reliable.
  #
  # The image tag is pinned to the repo's resolved @playwright/test version so the
  # browser/renderer matches the baselines. Derive it from package-lock.json.
  pw_version="$(node -e "process.stdout.write(require('${webapp}/package-lock.json').packages['node_modules/@playwright/test'].version)")"
  if [ -z "${pw_version}" ]; then
    echo "FAIL: could not resolve @playwright/test version from package-lock.json." >&2
    exit 1
  fi
  pw_image="mcr.microsoft.com/playwright:v${pw_version}-jammy"
  echo "Running @visual specs in ${pw_image} (shared netns with ${container}, base http://localhost:8080)..."
  # --ipc=host avoids Chromium /dev/shm exhaustion; --network container: shares the
  # app's netns; mount the repo so the specs + committed baselines are visible.
  docker run --rm --ipc=host --network "container:${container}" \
    -v "${repo_root}:/work" -w /work/docs-web/src/main/webapp \
    -e PLAYWRIGHT_BASE_URL="http://localhost:8080" \
    -e CI="${CI:-}" \
    "${pw_image}" \
    npx playwright test visual.spec --grep @visual "$@"
else
  # FUNCTIONAL suite on the host — EXCLUDE the pixel-comparison @visual specs (their
  # baselines match the jammy container, not this runner's fonts; the dedicated visual
  # job above covers them). The deterministic functional specs (incl. German-overflow)
  # still run at both viewports.
  #
  # Also EXCLUDE @flaky specs by default: these are KNOWN-flaky specs, each tracked by a
  # GitHub issue, quarantined so a nondeterministic test-timing failure can never fail a
  # release build. The nightly regression monitor sets E2E_INCLUDE_FLAKY=1 to run them for
  # ongoing triage (so we can later decide per-issue: fix the test, or it's a real bug).
  grep_invert="@visual"
  if [ "${E2E_INCLUDE_FLAKY:-}" != "1" ]; then
    grep_invert="@visual|@flaky"
  fi
  export PLAYWRIGHT_BASE_URL="http://localhost:${host_port}"
  echo "Running Playwright FUNCTIONAL e2e suite (excluding ${grep_invert}) against ${PLAYWRIGHT_BASE_URL}..."
  cd "${webapp}"
  npx playwright test --grep-invert "${grep_invert}" "$@"

  # #187 CONTROL GATE. e2e/cleanup-control.spec.ts holds two tests that are SUPPOSED to
  # fail — a body failure surviving a hanging cleanup, and a broken cleanup after a green
  # body going red — so they are skipped by the run above (E2E_CLEANUP_CONTROL unset) and
  # asserted here by OUTCOME instead. Without this, the deferred-cleanup fixture could
  # silently regress to swallowing teardown errors and every suite would still read green.
  # Runs only once the functional suite passed (set -e), against the same live app.
  echo "Verifying the deferred-cleanup control specs (#187)..."
  node scripts/verify-cleanup-controls.mjs
fi
