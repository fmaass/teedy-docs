#!/usr/bin/env bash
# CI/local runner for the real-Chrome browser-harness smoke suite (scripts/e2e-browser-harness.sh).
#
# The harness itself does NOT boot the app and does NOT launch a browser — it assumes
# a RUNNING Teedy at E2E_BASE_URL and attaches to a RUNNING Chrome over CDP via the
# browser-harness daemon. This wrapper supplies both, then runs the trimmed harness and
# tears everything down. It is the single entrypoint both CI and a local developer call,
# mirroring scripts/e2e-run.sh's container lifecycle.
#
# What it does:
#   1. Boots the production Docker image standalone (embedded H2, default admin/admin) on
#      port 8080 and waits for /api/app to serve.
#   2. Launches the runner's real Google Chrome HEADLESS with a dedicated (non-default)
#      profile and --remote-debugging-port, then parses the "DevTools listening on ws://…"
#      line from Chrome's stderr. Parsing the WS URL is robust across Chrome versions —
#      Chrome 147+ returns 404 for the /json/version HTTP endpoint on the default target,
#      so BU_CDP_URL (which resolves via /json/version) is unreliable; BU_CDP_WS (a direct
#      websocket URL) is not. A dedicated non-default profile + explicit debug port also
#      skips the M144 "Allow remote debugging" per-attach dialog (that only guards the
#      user's default profile), so this runs unattended.
#   3. Exports BU_CDP_WS so the browser-harness daemon attaches to THAT Chrome (no display
#      needed — headless), and runs scripts/e2e-browser-harness.sh. A non-zero harness exit
#      fails this script (and thus the CI job).
#
# Usage:
#   E2E_IMAGE=teedy-e2e:local scripts/e2e-harness-run.sh    # reuse an already-built image
#   scripts/e2e-harness-run.sh                              # build ./ as teedy-e2e:local first
#
# Env:
#   E2E_IMAGE     image ref to boot (default: build ./ as teedy-e2e:local)
#   E2E_PORT      host port to expose 8080 on (default 8080)
#   E2E_TIMEOUT   seconds to wait for /api/app readiness (default 180)
#   CHROME_BIN    path to the Chrome/Chromium binary (default: autodetect)
#   E2E_CDP_PORT  Chrome remote-debugging port (default 9222); set when 9222 is
#                 already bound on the host (e.g. an ssh tunnel)
#   E2E_EXPECT_VERSION   REQUIRED — passed through to the harness version gate, which
#                        has no default and fails fast if it is unset (CI derives it
#                        from the checked-out pom.xml; a local caller must export it)
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

image="${E2E_IMAGE:-}"
host_port="${E2E_PORT:-8080}"
cdp_port="${E2E_CDP_PORT:-9222}"
max_wait="${E2E_TIMEOUT:-180}"
container="teedy-harness-$$"
chrome_profile=""
chrome_pid=""
chrome_log="$(mktemp)"

cleanup() {
  # Stop the browser-harness daemon so it does not linger holding the CDP socket.
  BU_NAME="harness-$$" browser-harness --reload >/dev/null 2>&1 || true
  # Only kill the Chrome we launched. A captured PID can be recycled to an unrelated process
  # once our Chrome exits, so confirm the PID is (a) still alive (kill -0) and (b) still a
  # Chrome/Chromium process by its command name before signalling it. Never a pattern/pkill.
  if [ -n "${chrome_pid}" ] && kill -0 "${chrome_pid}" 2>/dev/null; then
    pid_comm="$(ps -p "${chrome_pid}" -o comm= 2>/dev/null || true)"
    # Exact-match an allowlist of Chrome/Chromium process command names (comm truncates to 15 chars),
    # never a substring/pattern — so e.g. an unrelated "chromatic" process is not mistaken for ours.
    case "${pid_comm}" in
      chrome|chromium|chromium-browse|google-chrome|headless_shell|chrome_crashpad)
        kill "${chrome_pid}" >/dev/null 2>&1 || true ;;
    esac
  fi
  [ -n "${chrome_profile}" ] && rm -rf "${chrome_profile}" >/dev/null 2>&1 || true
  rm -f "${chrome_log}" >/dev/null 2>&1 || true
  echo "::group::container logs (${container})"
  docker logs "${container}" 2>&1 | tail -n 200 || true
  echo "::endgroup::"
  docker rm -f "${container}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# --- 1. Build the image if one was not supplied ------------------------------
if [ -z "${image}" ]; then
  image="teedy-e2e:local"
  war_glob=("${repo_root}"/docs-web/target/docs-web-*.war)
  if [ ! -e "${war_glob[0]}" ]; then
    echo "Building production WAR (./mvnw -Pprod -DskipTests clean install)..."
    "${repo_root}/mvnw" -f "${repo_root}/pom.xml" -Pprod -DskipTests clean install
  fi
  echo "Building Docker image ${image}..."
  docker build -t "${image}" "${repo_root}"
fi

# --- 2. Boot the RC container ------------------------------------------------
echo "Starting container from ${image} (embedded H2, port ${host_port})..."
docker run -d --name "${container}" -p "${host_port}:8080" "${image}" >/dev/null

app_url="http://localhost:${host_port}/api/app"
echo "Waiting up to ${max_wait}s for ${app_url} to return HTTP 200..."
deadline=$(( $(date +%s) + max_wait ))
while true; do
  if [ "$(docker inspect -f '{{.State.Running}}' "${container}" 2>/dev/null || echo false)" != "true" ]; then
    echo "FAIL: container exited before serving /api/app."
    exit 1
  fi
  code="$(curl -s -o /dev/null -w '%{http_code}' "${app_url}" || echo 000)"
  if [ "${code}" = "200" ]; then
    echo "OK: /api/app returned HTTP 200 — app is up."
    break
  fi
  if [ "$(date +%s)" -ge "${deadline}" ]; then
    echo "FAIL: /api/app did not return 200 within ${max_wait}s (last code: ${code})."
    exit 1
  fi
  sleep 3
done

# --- 3. Launch headless Chrome with remote debugging -------------------------
chrome_bin="${CHROME_BIN:-}"
if [ -z "${chrome_bin}" ]; then
  for c in google-chrome google-chrome-stable chromium chromium-browser chrome; do
    if command -v "${c}" >/dev/null 2>&1; then chrome_bin="$(command -v "${c}")"; break; fi
  done
fi
if [ -z "${chrome_bin}" ] || [ ! -x "${chrome_bin}" ]; then
  echo "FAIL: no Chrome/Chromium binary found (set CHROME_BIN)." >&2
  exit 1
fi
echo "Using Chrome: ${chrome_bin} ($("${chrome_bin}" --version 2>/dev/null || echo '?'))"

chrome_profile="$(mktemp -d)"
# Headless real Chrome with a DEDICATED profile + explicit debug port (skips the
# M144 default-profile "Allow remote debugging" dialog). --remote-allow-origins=*
# so the daemon's ws:// upgrade Origin is accepted. --no-sandbox is required as the
# CI runner runs as root; harmless locally. --window-size=1280,900 gives the +N
# tag-overflow layout probe (check 14) a desktop-sized viewport — headless Chrome
# otherwise defaults to 800x600, below which the teleported popover falls off-screen
# and the "not clipped" assertion fails on a non-bug (mirrors Playwright's 1280-wide
# desktop project).
"${chrome_bin}" \
  --headless=new --no-first-run --no-default-browser-check --disable-gpu \
  --no-sandbox --disable-dev-shm-usage --window-size=1280,900 \
  --user-data-dir="${chrome_profile}" \
  --remote-debugging-port="${cdp_port}" --remote-allow-origins='*' \
  about:blank >"${chrome_log}" 2>&1 &
chrome_pid=$!

echo "Waiting for Chrome DevTools websocket..."
ws=""
ws_deadline=$(( $(date +%s) + 30 ))
while [ "$(date +%s)" -lt "${ws_deadline}" ]; do
  if ! kill -0 "${chrome_pid}" >/dev/null 2>&1; then
    echo "FAIL: Chrome exited before opening the DevTools websocket. Log:" >&2
    cat "${chrome_log}" >&2 || true
    exit 1
  fi
  ws="$(grep -oE 'ws://[^ ]*/devtools/browser/[a-f0-9-]+' "${chrome_log}" | head -n1 || true)"
  [ -n "${ws}" ] && break
  sleep 1
done
if [ -z "${ws}" ]; then
  echo "FAIL: could not find the DevTools websocket URL in Chrome's log within 30s. Log:" >&2
  cat "${chrome_log}" >&2 || true
  exit 1
fi
# The log prints the IPv6 loopback form (ws://[::1]:<port>/...); force IPv4 loopback,
# which the daemon connects to reliably on CI.
ws="${ws//\[::1\]/127.0.0.1}"
echo "OK: Chrome DevTools at ${ws}"

# --- 4. Run the harness against the booted app + attached Chrome -------------
# This runner OWNS the target's disposability: it just booted a throwaway container from
# ${image} on embedded H2 (step 2) and tears it down on exit (cleanup trap). That is exactly
# the disposable instance the harness's E2E_ALLOW_SEED guard requires, so set the opt-in here.
# A caller running the harness by hand against some other E2E_BASE_URL must set it themselves.
echo "Running browser-harness smoke suite..."
BU_NAME="harness-$$" BU_CDP_WS="${ws}" \
  E2E_BASE_URL="http://localhost:${host_port}" \
  E2E_ALLOW_SEED=1 \
  bash "${repo_root}/scripts/e2e-browser-harness.sh"
