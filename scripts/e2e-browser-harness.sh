#!/usr/bin/env bash
# Browser-harness (real Chrome via CDP) e2e checks for Teedy.
#
# Complements the Playwright suite (docs-web/src/main/webapp/e2e) with an
# INDEPENDENT browser engine + driver: Playwright drives its own bundled
# Chromium; this drives the locally installed Chrome through the browser-use
# harness. Both run against the same booted instance. The feature→coverage map
# (which Playwright spec overlaps/complements which check here) lives in
# scripts/e2e-coverage-matrix.md.
#
# TRIMMED FOR CI (GitHub #76): checks duplicated by the Playwright
# desktop/mobile/visual suite were removed; only checks genuinely unique to the
# real-Chrome CDP harness (exact version gate, account-sessions & monitoring
# content, the not-clipped +N overflow layout probe, and the authoritative
# XSS-inert read-back) plus the login prerequisite remain. Full overlap analysis:
# scripts/e2e-coverage-matrix.md.
#
# PRECONDITION — this script does NOT boot anything. It assumes a RUNNING Teedy
# at E2E_BASE_URL (default http://localhost:8080) with the default admin/admin
# credentials — a fresh embedded-H2 boot of the current image, exactly what
# scripts/e2e-run.sh builds. Typical combined run:
#
#   ./mvnw -Pprod -DskipTests clean install && docker build -t teedy-e2e:local .
#   docker run -d --name teedy-bh -p 8080:8080 teedy-e2e:local
#   # wait for /api/app to answer, then:
#   scripts/e2e-browser-harness.sh
#   docker rm -f teedy-bh
#
# Requires: the `browser-harness` CLI (browser-use) on PATH, a local Chrome with
# CDP remote debugging allowed, curl, python3.
#
# Data hygiene: every artifact this run creates (documents, tags) is stamped with a
# unique RUN token, so reruns against a long-lived instance never collide. Created
# documents are trashed at the end (best-effort cleanup).
#
# Version gate: check 1 asserts current_version == E2E_EXPECT_VERSION. That env var
# is REQUIRED (no hardcoded default) — CI derives it from the checked-out pom.xml and
# a local caller must export it. An unset value fails the harness fast. A mismatch
# FAILS the harness so it can never certify a stale image.
#
# Checks performed (each FAIL exits nonzero at the end):
#   1. /api/app answers and current_version == E2E_EXPECT_VERSION (required)  [api]
#   2. native form login admin/admin lands in the app shell (logged-in UI)   [browser]
#   8. Admin settings › account sessions — the self-service sessions table
#      renders the current-session row (the computed "current" marker)         [browser]
#   9. Admin settings › monitoring — the server-log viewer renders log rows    [browser]
#  14. Tag overflow (v3.2.2 D) — a document with 5 tags shows a focusable "+2"
#      control whose popover (teleported to <body>) reveals the 4th/5th tag names
#      and is NOT clipped (rect inside the viewport) — a real-Chrome layout check
#      with a CDP screenshot; activating it does not navigate the row           [browser]
#  21. Rich description (v3.5.0 #38, security-critical) — POST /document/:id with a
#      description mixing legitimate formatting and a hostile payload; the stored HTML
#      read back via /api/document is inert (no <script/onerror/javascript:) while the
#      <strong>/<a href="https:// survived; the Quill editor mounts on the edit view [browser+api]
#
# Every browser check performs a real interaction (search / click / open / act)
# plus an assertion on rendered DOM or authoritative API state, and saves a
# screenshot. No check merely loads a page and claims coverage.
#
# Artifacts: e2e-artifacts/browser-harness/*.png + checks.log (gitignored dir).
set -uo pipefail

base_url="${E2E_BASE_URL:-http://localhost:8080}"
# E2E_EXPECT_VERSION is REQUIRED — there is deliberately no hardcoded version default
# anywhere, so the harness can never silently certify against a stale expectation. CI
# derives it from the checked-out pom.xml; a local caller must export it.
if [ -z "${E2E_EXPECT_VERSION:-}" ]; then
  echo "FATAL: E2E_EXPECT_VERSION is not set — refusing to run the version gate without an explicit expected version (derive it from pom.xml, as CI does). See scripts/check-version-consistency.sh." >&2
  exit 2
fi
expect_version="${E2E_EXPECT_VERSION}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
art_dir="${repo_root}/e2e-artifacts/browser-harness"
mkdir -p "${art_dir}"
cookie_jar="$(mktemp)"
trap 'rm -f "${cookie_jar}"' EXIT

# Unique per-run token so reruns against a long-lived instance never collide.
run_token="bh-$(date +%s)-$$"

fail=0
note() { echo "[$1] $2" | tee -a "${art_dir}/checks.log"; [ "$1" = "FAIL" ] && fail=1 || true; }

echo "== Teedy browser-harness e2e against ${base_url} (run ${run_token}) ==" | tee "${art_dir}/checks.log"

# --- Check 1: /api/app version (hard gate) ------------------------------------
app_json="$(curl -sf "${base_url}/api/app" || true)"
version="$(printf '%s' "${app_json}" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("current_version",""))' 2>/dev/null || true)"
if [ -z "${version}" ]; then
  note FAIL "check1: /api/app unreachable or no current_version (got: ${app_json:0:120})"
elif [ "${version}" != "${expect_version}" ]; then
  note FAIL "check1: version mismatch — expected ${expect_version}, app reports ${version}"
else
  note OK "check1: /api/app current_version=${version} (== ${expect_version})"
fi

# --- Seed: REST login + deterministic, uniquely-named test data ---------------
# We seed the data-heavy / flaky-to-click state via REST (login, tags, documents),
# then drive the REAL interaction + assertion for each feature area in the browser
# below. Seed IDs are handed to the browser via env.
curl -sf -c "${cookie_jar}" -X POST -d "username=admin&password=admin&remember=false" \
  "${base_url}/api/user/login" >/dev/null \
  || note FAIL "seed: REST login admin/admin failed"

api_put() { curl -sf -b "${cookie_jar}" -X PUT "$@"; }
api_post() { curl -sf -b "${cookie_jar}" -X POST "$@"; }
json_field() { python3 -c "import sys,json;print(json.load(sys.stdin).get('$1',''))"; }

# --- Seed for the tag-overflow check (14) -------------------------------------
# A document carrying FIVE tags so the list row deterministically overflows (>3)
# and renders the "+2" TagOverflow control the popover check drives.
ovf_tag_ids=""
ovf_tag_4=""
ovf_tag_5=""
for n in 1 2 3 4 5; do
  tid="$(api_put -d "name=${run_token}-ovf${n}&color=#2aabd2" "${base_url}/api/tag" | json_field id || true)"
  [ -z "${tid}" ] && note FAIL "seed: could not create overflow tag ${n}"
  ovf_tag_ids="${ovf_tag_ids} -d tags=${tid}"
  [ "${n}" = "4" ] && ovf_tag_4="${tid}"
  [ "${n}" = "5" ] && ovf_tag_5="${tid}"
done
ovf_doc_title="${run_token} Overflow Document"
# shellcheck disable=SC2086
ovf_doc_id="$(api_put --data-urlencode "title=${ovf_doc_title}" -d "language=eng" ${ovf_tag_ids} \
  "${base_url}/api/document" | json_field id || true)"
[ -z "${ovf_doc_id}" ] && note FAIL "seed: could not create overflow document"

# --- Seed for the rich-description check (21) ---------------------------------
# Rich description (#38): a RUN-stamped document the sanitizer check updates with a
# mixed legitimate+hostile description, then reads back to assert the XSS payload was
# stripped and the legitimate formatting survived.
rich_doc_title="${run_token} Rich Description Document"
rich_doc_id="$(api_put --data-urlencode "title=${rich_doc_title}" \
  -d "language=eng" "${base_url}/api/document" | json_field id || true)"
[ -z "${rich_doc_id}" ] && note FAIL "seed: could not create rich-description document"

echo "[seed] ovf_doc=${ovf_doc_id} rich_doc=${rich_doc_id}" \
  | tee -a "${art_dir}/checks.log"

# --- Checks 2, 8, 9, 14, 21: real-browser flow --------------------------------
BH_OUT="$(BH_BASE_URL="${base_url}" BH_ART="${art_dir}" \
  BH_RUN="${run_token}" \
  BH_OVF_DOC_TITLE="${ovf_doc_title}" BH_OVF_TAG4="${run_token}-ovf4" BH_OVF_TAG5="${run_token}-ovf5" \
  BH_RICH_DOC_ID="${rich_doc_id}" \
  browser-harness <<'PY'
import json, os, time, urllib.parse

base = os.environ["BH_BASE_URL"]
art = os.environ["BH_ART"]
run = os.environ.get("BH_RUN", "")
ovf_doc_title = os.environ.get("BH_OVF_DOC_TITLE", "")
ovf_tag4 = os.environ.get("BH_OVF_TAG4", "")
ovf_tag5 = os.environ.get("BH_OVF_TAG5", "")
rich_doc_id = os.environ.get("BH_RICH_DOC_ID", "")
results = {}

def shot(name):
    capture_screenshot(path=os.path.join(art, name + ".png"))

def cur_url():
    return page_info()["url"]

# Ensure we start from a real page on the target origin so relative fetch() works.
new_tab(base + "/#/login")
wait_for_load(); time.sleep(1)

# --- Check 2: native form login -----------------------------------------------
js("""
  (() => {
    const u = document.querySelector('input[type=text], input[autocomplete=username], #username');
    const p = document.querySelector('input[type=password]');
    const set = (el, v) => { el.value = v; el.dispatchEvent(new Event('input', {bubbles:true})); };
    set(u, 'admin'); set(p, 'admin');
  })();
""")
time.sleep(0.3)
# PrimeVue buttons default to type="button", so a bare `form button` selector can
# hit "Forgot password" first — pick the sign-in button by its visible label.
js("""
  (() => {
    const btns = [...document.querySelectorAll('button')];
    const signin = btns.find(b => /sign\\s*in/i.test(b.textContent));
    (signin ?? document.querySelector('form button[type=submit]')).click();
  })();
""")
time.sleep(3); wait_for_load()
results["login"] = ("/login" not in cur_url(), cur_url())
shot("02-after-login")

# --- Check 8: admin settings › account sessions (self-service) ---------------
goto_url(base + "/#/settings/account"); time.sleep(2); wait_for_load()
sess = js("""
  return (() => {
    const table = document.querySelector('.sessions-table');
    const rows = table ? table.querySelectorAll('tbody tr').length : 0;
    const current = !!document.querySelector('.sessions-table .p-tag, .sessions-table [class*=tag]');
    const heading = [...document.querySelectorAll('.sessions-card, .sessions-header, h3')]
      .some(e => /session/i.test(e.textContent||''));
    return {rows, current, heading};
  })();
""")
# Require the computed current-session marker (the "current" Tag), not just any
# row, so the label ("current-session row") matches the assertion.
sess_ok = bool(sess.get("heading")) and int(sess.get("rows") or 0) >= 1 and bool(sess.get("current"))
results["account_sessions"] = (sess_ok, f"sessions rows={sess.get('rows')} current_marker={sess.get('current')}")
shot("08-account-sessions")

# --- Check 9: admin settings › monitoring log viewer -------------------------
goto_url(base + "/#/settings/monitoring"); time.sleep(3); wait_for_load()
mon = js("""
  return (() => {
    const table = document.querySelector('.log-table');
    const rows = table ? table.querySelectorAll('tbody tr').length : 0;
    const total = document.querySelector('.log-total');
    return {rows, total: total ? total.textContent.trim() : ''};
  })();
""")
mon_ok = int(mon.get("rows") or 0) >= 1
results["monitoring_logs"] = (mon_ok, f"log rows={mon.get('rows')} total='{mon.get('total')}'")
shot("09-monitoring-logs")

# --- Check 14 (behavior D): +N tag overflow popover (real-Chrome layout) -------
# The overflow doc carries 5 tags: the list row shows the first 3 inline plus a
# focusable "+2" TagOverflow control whose popover teleports to <body> (escaping the
# DataTable overflow clip) and reveals the 4th and 5th tag names. A real-Chrome
# layout concern worth a CDP screenshot: assert the popover is visible AND within the
# viewport (not clipped), and that it reveals the hidden tag names.
goto_url(base + "/#/document"); time.sleep(1.5); wait_for_load()
# Search for the overflow doc so its row is on the page.
js("""
  (() => {
    const el = document.querySelector('.search-input input, input.search-input, .search-row input');
    if (el) { el.value = arguments0; el.dispatchEvent(new Event('input', {bubbles:true})); }
  })();
""".replace("arguments0", json.dumps(ovf_doc_title)))
time.sleep(2.5); wait_for_load()
ovf_before = js("""
  return (() => {
    const el = document.querySelector('.tag-overflow');
    if (!el) return {present: false};
    return {present: true, text: el.textContent.trim(), expanded: el.getAttribute('aria-expanded')};
  })();
""")
# Activate the overflow control (click) and read the teleported popover panel.
js("""
  (() => { const el = document.querySelector('.tag-overflow'); if (el) el.click(); })();
""")
time.sleep(1.0)
ovf_after = js("""
  return (() => {
    const panel = document.querySelector('.tag-overflow-panel');
    if (!panel) return {open: false};
    const names = [...panel.querySelectorAll('.teedy-tag')].map(e => e.textContent.trim());
    const r = panel.getBoundingClientRect();
    // "Not clipped": the panel rect is non-empty and inside the viewport bounds.
    const inViewport = r.width > 0 && r.height > 0 && r.top >= 0 && r.left >= 0
      && r.bottom <= (window.innerHeight + 1) && r.right <= (window.innerWidth + 1);
    const ctrl = document.querySelector('.tag-overflow');
    return {open: true, names, inViewport, expanded: ctrl ? ctrl.getAttribute('aria-expanded') : null,
            still_on_list: location.hash.replace(/\\?.*$/, '') === '#/document'};
  })();
""")
shot("14-tag-overflow-popover")
names = ovf_after.get("names", []) if isinstance(ovf_after, dict) else []
overflow_ok = (
    bool(ovf_before.get("present"))
    and "+2" in (ovf_before.get("text") or "")
    and bool(ovf_after.get("open"))
    and ovf_tag4 in names and ovf_tag5 in names
    and bool(ovf_after.get("inViewport"))         # not clipped by the DataTable overflow
    and ovf_after.get("expanded") == "true"
    and bool(ovf_after.get("still_on_list"))      # activating it did not navigate the row
)
results["tag_overflow"] = (overflow_ok, f"before={ovf_before} after_names={names} inViewport={ovf_after.get('inViewport') if isinstance(ovf_after, dict) else None}")

# --- Check 21 (#38): rich description sanitized (XSS-inert) --------------------
# Security-critical: PUT /api/document with a description mixing legitimate formatting and
# a hostile payload; read it back via /api/document and assert the stored HTML is inert —
# NO <script, NO onerror, NO javascript: — while the legitimate <strong>/<a href="https://
# survived. Also open the edit view and confirm the Quill editor (.ql-toolbar) is present.
rich_desc = ('<p><strong>bold</strong> <a href="https://example.com">link</a></p>'
             '<script>window.__x=1</script>'
             '<img src=x onerror="window.__y=1">'
             '<a href="javascript:window.__z=1">bad</a>')
rich_put = js("""
  return (() => fetch(location.origin + '/api/document/arg_id', {
    method: 'POST', credentials: 'include',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: new URLSearchParams({title: arg_title, language: 'eng', description: arg_desc}).toString()
  }).then(r => r.status).catch(() => 0))();
""".replace("arg_id", rich_doc_id)
   .replace("arg_title", json.dumps(run + " Rich Description Document"))
   .replace("arg_desc", json.dumps(rich_desc)))
time.sleep(0.8)
stored = js("""
  return (() => fetch(location.origin + '/api/document/arg_id', {credentials:'include'})
    .then(r=>r.json()).then(d => d.description || '').catch(()=> ''))();
""".replace("arg_id", rich_doc_id))
stored_l = (stored or "").lower()
inert = ("<script" not in stored_l) and ("onerror" not in stored_l) and ("javascript:" not in stored_l)
legit = ("<strong" in stored_l) and ('href="https://example.com"' in stored_l)
# Confirm the Quill editor mounts on the edit view (.ql-toolbar rendered at runtime).
goto_url(base + f"/#/document/edit/{rich_doc_id}"); time.sleep(2.5); wait_for_load()
quill_present = js("return (() => !!document.querySelector('.ql-toolbar') && !!document.querySelector('.ql-editor'))();")
shot("21-rich-description")
rich_ok = (int(rich_put or 0) == 200) and inert and legit and bool(quill_present)
results["rich_description_sanitized"] = (rich_ok, f"put={rich_put} inert={inert} legit={legit} quill={quill_present} stored={stored[:160]!r}")

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
    "login": "check2: form login admin/admin entered the app",
    "account_sessions": "check8: account settings sessions table rendered the current-session row",
    "monitoring_logs": "check9: monitoring log viewer rendered log rows",
    "tag_overflow": "check14: +N tag overflow popover revealed the hidden tags, not clipped (behavior D)",
    "rich_description_sanitized": "check21: rich description stored inert (no script/onerror/javascript:) with legit formatting kept + Quill editor present (#38)",
}
order = list(labels.keys())
bad = 0
for key in order:
    if key not in res:
        print(f"[FAIL] {labels[key]} (no result emitted)"); bad += 1; continue
    ok, detail = res[key]
    print(f"[{'OK' if ok else 'FAIL'}] {labels[key]} — {detail}")
    bad += 0 if ok else 1
# Surface any unexpected extra keys too.
for key, (ok, detail) in res.items():
    if key not in labels:
        print(f"[{'OK' if ok else 'FAIL'}] {key} — {detail}")
        bad += 0 if ok else 1
sys.exit(1 if bad else 0)
PY

# --- Cleanup: trash the documents this run created -----------------------------
# Best-effort; every artifact is uniquely RUN-token stamped so a missed cleanup
# never collides with a later run. The top-of-script cookie_jar admin session is
# still valid (the harness never logs it out).
for d in "${ovf_doc_id:-}" "${rich_doc_id:-}"; do
  [ -n "${d}" ] || continue
  curl -sf -b "${cookie_jar}" -X DELETE "${base_url}/api/document/${d}" >/dev/null 2>&1 \
    && echo "[cleanup] trashed document ${d}" | tee -a "${art_dir}/checks.log" \
    || echo "[cleanup] could not trash document ${d} (non-fatal)" | tee -a "${art_dir}/checks.log"
done

if [ "${fail}" -ne 0 ]; then
  echo "== RESULT: FAIL (see ${art_dir}) =="
  exit 1
fi
echo "== RESULT: all browser-harness checks passed (artifacts: ${art_dir}) =="
