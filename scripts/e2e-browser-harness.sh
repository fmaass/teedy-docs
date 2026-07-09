#!/usr/bin/env bash
# Browser-harness (real Chrome via CDP) e2e checks for Teedy.
#
# Complements the Playwright suite (docs-web/src/main/webapp/e2e) with an
# INDEPENDENT browser engine + driver: Playwright drives its own bundled
# Chromium; this drives the locally installed Chrome through the browser-use
# harness. Both run against the same booted instance.
#
# Assumes a RUNNING Teedy at E2E_BASE_URL (default http://localhost:8080) with
# the default admin/admin credentials (i.e. a fresh embedded-H2 boot, exactly
# what scripts/e2e-run.sh provides). Typical combined run:
#
#   scripts/e2e-run.sh                        # boots image + Playwright suite
#   docker run -d --name teedy-bh -p 8080:8080 teedy-e2e:local
#   scripts/e2e-browser-harness.sh            # this script
#   docker rm -f teedy-bh
#
# Requires: the `browser-harness` CLI (browser-use) on PATH, a local Chrome
# with CDP remote debugging allowed, curl, python3.
#
# Checks performed (each FAIL exits nonzero at the end):
#   1. /api/app answers and reports a version (compared against
#      E2E_EXPECT_VERSION when that env var is set)
#   2. native form login admin/admin lands in the app shell (logged-in UI)
#   3. cold-load deep link /#/document?search=…&tags=…&exclude=… retains ALL
#      query params after hydration (URL retention only; API-level filter
#      coverage lives in the Playwright suite)
#   4. unauthenticated deep link to /#/settings/users is redirected to /login
#      (route-guard probe; server-side authz is covered by REST tests)
#   5. screenshots of login, document list, and settings saved under
#      e2e-artifacts/browser-harness/ for human review
#
# Artifacts: e2e-artifacts/browser-harness/*.png + checks.log (gitignored dir).
set -uo pipefail

base_url="${E2E_BASE_URL:-http://localhost:8080}"
expect_version="${E2E_EXPECT_VERSION:-}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
art_dir="${repo_root}/e2e-artifacts/browser-harness"
mkdir -p "${art_dir}"
cookie_jar="$(mktemp)"
trap 'rm -f "${cookie_jar}"' EXIT

fail=0
note() { echo "[$1] $2" | tee -a "${art_dir}/checks.log"; [ "$1" = "FAIL" ] && fail=1 || true; }

echo "== Teedy browser-harness e2e against ${base_url} ==" | tee "${art_dir}/checks.log"

# --- Check 1: /api/app version ------------------------------------------------
app_json="$(curl -sf "${base_url}/api/app" || true)"
version="$(printf '%s' "${app_json}" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("current_version",""))' 2>/dev/null || true)"
if [ -z "${version}" ]; then
  note FAIL "check1: /api/app unreachable or no current_version (got: ${app_json:0:120})"
else
  note OK "check1: /api/app current_version=${version}"
  if [ -n "${expect_version}" ] && [ "${version}" != "${expect_version}" ]; then
    note FAIL "check1: version mismatch — expected ${expect_version}, app reports ${version}"
  fi
fi

# --- Seed: login via REST, create two tags for the deep-link probe -------------
curl -sf -c "${cookie_jar}" -X POST -d "username=admin&password=admin&remember=false" \
  "${base_url}/api/user/login" >/dev/null \
  || note FAIL "seed: REST login admin/admin failed"
mk_tag() {
  curl -sf -b "${cookie_jar}" -X PUT -d "name=$1&color=#2aabd2" "${base_url}/api/tag" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])'
}
tag_in="$(mk_tag bh-include || true)"
tag_ex="$(mk_tag bh-exclude || true)"
if [ -z "${tag_in}" ] || [ -z "${tag_ex}" ]; then
  note FAIL "seed: could not create probe tags via /api/tag"
fi

# --- Checks 2-5: real-browser flow ---------------------------------------------
BH_OUT="$(BH_BASE_URL="${base_url}" BH_ART="${art_dir}" BH_TAG_IN="${tag_in}" BH_TAG_EX="${tag_ex}" \
  browser-harness <<'PY'
import json, os, time, urllib.parse

base = os.environ["BH_BASE_URL"]
art = os.environ["BH_ART"]
tag_in = os.environ.get("BH_TAG_IN", "")
tag_ex = os.environ.get("BH_TAG_EX", "")
results = {}

def shot(name):
    capture_screenshot(path=os.path.join(art, name + ".png"))

# Check 4 first (unauthenticated): deep link to an admin settings route
new_tab(base + "/#/settings/users")
wait_for_load()
time.sleep(1.5)
url = page_info()["url"]
results["guard_redirect"] = ("/login" in url, url)
shot("01-unauthenticated-guard")

# Check 2: native form login
goto_url(base + "/#/login")
wait_for_load(); time.sleep(1)
js("""
  const u = document.querySelector('input[type=text], input[autocomplete=username], #username');
  const p = document.querySelector('input[type=password]');
  const set = (el, v) => { el.value = v; el.dispatchEvent(new Event('input', {bubbles:true})); };
  set(u, 'admin'); set(p, 'admin');
""")
time.sleep(0.3)
# PrimeVue buttons default to type="button", so a bare `form button` selector can
# hit "Forgot password" first — pick the sign-in button by its visible label.
js("""
  const btns = [...document.querySelectorAll('button')];
  const signin = btns.find(b => /sign\\s*in/i.test(b.textContent));
  (signin ?? document.querySelector('form button[type=submit]')).click();
""")
time.sleep(3); wait_for_load()
url = page_info()["url"]
in_app = "/login" not in url
results["login"] = (in_app, url)
shot("02-after-login")

# Check 3: cold-load deep link with search+tags+exclude (fresh navigation)
deep = base + f"/#/document?search=bh-probe&tags={tag_in}&exclude={tag_ex}"
goto_url(deep); time.sleep(1)
# force a true cold load of the SPA with the deep-link intact
js("location.reload()")
time.sleep(4); wait_for_load()
url = page_info()["url"]
q = urllib.parse.parse_qs(urllib.parse.urlparse(url.replace('/#/', '/')).query)
kept = (q.get("tags") == [tag_in]) and (q.get("exclude") == [tag_ex]) and (q.get("search") == ["bh-probe"])
results["hydration_roundtrip"] = (kept, url)
shot("03-deep-link-hydrated")

# Check 5: settings reachable as admin
goto_url(base + "/#/settings/users"); time.sleep(2); wait_for_load()
url = page_info()["url"]
results["admin_settings"] = ("/settings/users" in url, url)
shot("04-admin-settings")

print("BH_RESULTS " + json.dumps(results))
PY
)" || note FAIL "browser-harness invocation failed"
echo "${BH_OUT}" >> "${art_dir}/checks.log"

python3 - "$BH_OUT" <<'PY' || fail=1
import json, sys
line = next((l for l in sys.argv[1].splitlines() if l.startswith("BH_RESULTS ")), None)
if not line:
    print("[FAIL] no BH_RESULTS emitted"); sys.exit(1)
res = json.loads(line[len("BH_RESULTS "):])
labels = {
    "guard_redirect": "check4: unauthenticated /settings/users redirected to /login",
    "login": "check2: form login admin/admin entered the app",
    "hydration_roundtrip": "check3: deep-link kept search+tags+exclude after cold-load hydration",
    "admin_settings": "check5: admin can open /settings/users",
}
bad = 0
for key, (ok, url) in res.items():
    print(f"[{'OK' if ok else 'FAIL'}] {labels.get(key, key)} ({url})")
    bad += 0 if ok else 1
sys.exit(1 if bad else 0)
PY

if [ "${fail}" -ne 0 ]; then
  echo "== RESULT: FAIL (see ${art_dir}) =="
  exit 1
fi
echo "== RESULT: all browser-harness checks passed (artifacts: ${art_dir}) =="
