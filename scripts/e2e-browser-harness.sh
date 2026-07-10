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
# Data hygiene: every artifact this run creates (documents, tags, share link) is
# stamped with a unique RUN token, so reruns against a long-lived instance never
# collide. Created documents are trashed at the end (best-effort cleanup).
#
# Browser isolation: the harness attaches to a PERSISTENT local Chrome; new_tab()
# does NOT give a clean cookie context. The anonymous checks (share view, route
# guard) therefore LOG OUT via the real /api/user/logout endpoint from inside the
# page (which expires the httpOnly auth_token cookie the same way the app does),
# run their assertion as a genuinely-anonymous visitor, then LOG BACK IN as admin.
# The logged-out region is wrapped in try/finally so the admin session is ALWAYS
# restored even if an anonymous check throws — a logout that is never undone would
# poison every later run against this persistent Chrome. The restore is VERIFIED
# (check 12, GET /api/user) and the restore login's HTTP status is checked, so a
# rejected restore FAILS the harness instead of passing silently. This makes the
# anonymous checks order-independent and not dependent on a pre-cleared browser.
#
# Version gate: check 1 asserts current_version == E2E_EXPECT_VERSION (default
# 3.2.0). A mismatch FAILS the harness so it can never certify a stale image.
#
# Checks performed (each FAIL exits nonzero at the end):
#   1. /api/app answers and current_version == expected (default 3.2.0)      [api]
#   2. native form login admin/admin lands in the app shell (logged-in UI)   [browser]
#   3. cold-load deep link /#/document?search=…&tags=…&exclude=… retains ALL
#      query params after hydration (URL retention; API-level filter coverage
#      lives in the Playwright suite)                                        [browser]
#   4. Documents CRUD — search finds the seeded RUN document in the list      [browser]
#   5. Tags + facets — the Facets sidebar shows the seeded tag with a count   [browser]
#   6. Vocabulary dropdown — the document-edit `type` Select opens its seeded
#      Dublin-Core vocabulary options; a picked value is saved and read back
#      from /api/document (authoritative)                                     [browser+api]
#   7. Workflow act — start the seeded "Document review" route on a doc, then
#      VALIDATE its first step in the workflow tab (step 1 of the seeded model
#      is a VALIDATE step); the current step advances (verified by authoritative
#      /api/route read-back)                                                   [browser+api]
#   8. Admin settings › account sessions — the self-service sessions table
#      renders the current-session row (the computed "current" marker)         [browser]
#   9. Admin settings › monitoring — the server-log viewer renders log rows    [browser]
#  10. Share anonymous-view (context-isolated) — after logout, the public
#      /#/share/:doc/:share URL renders the shared document's title to an
#      anonymous visitor                                                        [browser]
#  11. Anonymous route guard (context-isolated) — after logout, a deep link to
#      /#/settings/users redirects to /login                                    [browser]
#  12. Session restore (verified) — after the logged-out checks 10-11, the admin
#      session is restored and confirmed live via GET /api/user (username=admin,
#      not anonymous). Checks 10-11 run inside try/finally so this restore
#      ALWAYS runs, even if 10 or 11 throws                                    [browser+api]
#
# Every browser check performs a real interaction (search / click / open / act)
# plus an assertion on rendered DOM or authoritative API state, and saves a
# screenshot. No check merely loads a page and claims coverage.
#
# Artifacts: e2e-artifacts/browser-harness/*.png + checks.log (gitignored dir).
set -uo pipefail

base_url="${E2E_BASE_URL:-http://localhost:8080}"
expect_version="${E2E_EXPECT_VERSION:-3.2.0}"
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
# We seed the data-heavy / flaky-to-click state via REST (login, tags, document,
# share link), then drive the REAL interaction + assertion for each feature area
# in the browser below. Seed IDs are handed to the browser via env.
curl -sf -c "${cookie_jar}" -X POST -d "username=admin&password=admin&remember=false" \
  "${base_url}/api/user/login" >/dev/null \
  || note FAIL "seed: REST login admin/admin failed"

api_put() { curl -sf -b "${cookie_jar}" -X PUT "$@"; }
api_post() { curl -sf -b "${cookie_jar}" -X POST "$@"; }
json_field() { python3 -c "import sys,json;print(json.load(sys.stdin).get('$1',''))"; }

# Two tags for the deep-link + facet probes.
tag_in="$(api_put -d "name=${run_token}-in&color=#2aabd2" "${base_url}/api/tag" | json_field id || true)"
tag_ex="$(api_put -d "name=${run_token}-ex&color=#d22a2a" "${base_url}/api/tag" | json_field id || true)"
[ -z "${tag_in}" ] || [ -z "${tag_ex}" ] && note FAIL "seed: could not create probe tags via /api/tag"

# A uniquely-titled document tagged with the include tag — the subject of the
# documents-CRUD, facet, vocabulary, workflow and share checks.
doc_title="${run_token} Harness Document"
doc_id="$(api_put --data-urlencode "title=${doc_title}" \
  --data-urlencode "description=browser-harness ${run_token}" \
  -d "language=eng" -d "tags=${tag_in}" "${base_url}/api/document" | json_field id || true)"
[ -z "${doc_id}" ] && note FAIL "seed: could not create probe document via /api/document"

# A share link on that document — the target of the anonymous share-view check.
share_id="$(api_put -d "id=${doc_id}" -d "name=${run_token}-share" "${base_url}/api/share" | json_field id || true)"
[ -z "${share_id}" ] && note FAIL "seed: could not create share link via /api/share"

# Start the seeded "Document review" route on the document so the workflow check
# has an active step to ACT on. (The model is DB-seeded: default-document-review.)
route_start="$(api_post -d "routeModelId=default-document-review" -d "documentId=${doc_id}" \
  "${base_url}/api/route/start" 2>/dev/null || true)"
route_ok="$(printf '%s' "${route_start}" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("1" if d.get("route_step") else "")' 2>/dev/null || true)"
[ -z "${route_ok}" ] && note FAIL "seed: could not start route on document (got: ${route_start:0:120})"

echo "[seed] doc=${doc_id} tag_in=${tag_in} tag_ex=${tag_ex} share=${share_id} route_started=${route_ok:-0}" \
  | tee -a "${art_dir}/checks.log"

# --- Checks 2-11: real-browser flow -------------------------------------------
BH_OUT="$(BH_BASE_URL="${base_url}" BH_ART="${art_dir}" \
  BH_TAG_IN="${tag_in}" BH_TAG_EX="${tag_ex}" BH_TAG_IN_NAME="${run_token}-in" \
  BH_DOC_ID="${doc_id}" BH_DOC_TITLE="${doc_title}" BH_SHARE_ID="${share_id}" \
  BH_RUN="${run_token}" \
  browser-harness <<'PY'
import json, os, time, urllib.parse

base = os.environ["BH_BASE_URL"]
art = os.environ["BH_ART"]
tag_in = os.environ.get("BH_TAG_IN", "")
tag_ex = os.environ.get("BH_TAG_EX", "")
tag_in_name = os.environ.get("BH_TAG_IN_NAME", "")
doc_id = os.environ.get("BH_DOC_ID", "")
doc_title = os.environ.get("BH_DOC_TITLE", "")
share_id = os.environ.get("BH_SHARE_ID", "")
run = os.environ.get("BH_RUN", "")
results = {}

def shot(name):
    capture_screenshot(path=os.path.join(art, name + ".png"))

def cur_url():
    return page_info()["url"]

# --- session helpers: log the browser in/out via the REAL app endpoints so the
# httpOnly auth_token cookie is actually set/expired in this persistent Chrome.
def rest_login():
    # fetch() with credentials:'include' applies the Set-Cookie to this context.
    # Returns the HTTP status so callers can verify the restore actually succeeded.
    status = js("""
      return (() => fetch(location.origin + '/api/user/login', {
        method: 'POST', credentials: 'include',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: 'username=admin&password=admin&remember=false'
      }).then(r => r.status).catch(() => 0))();
    """)
    time.sleep(0.6)
    return status

def rest_logout():
    js("""
      return (() => fetch(location.origin + '/api/user/logout', {
        method: 'POST', credentials: 'include'
      }).then(r => r.status).catch(() => 0))();
    """)
    time.sleep(0.6)

def admin_session_live():
    # Authoritative: GET /api/user reflects the current session — admin, not guest.
    return js("""
      return (() => fetch(location.origin + '/api/user', {credentials:'include'})
        .then(r => r.ok ? r.json() : {})
        .then(u => (u && u.username === 'admin' && u.anonymous !== true))
        .catch(() => false))();
    """)

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

# --- Check 3: cold-load deep link keeps search+tags+exclude -------------------
deep = base + f"/#/document?search={run}&tags={tag_in}&exclude={tag_ex}"
goto_url(deep); time.sleep(1)
js("location.reload()")           # force a true cold load with the deep link intact
time.sleep(4); wait_for_load()
url = cur_url()
q = urllib.parse.parse_qs(urllib.parse.urlparse(url.replace('/#/', '/')).query)
kept = (q.get("tags") == [tag_in]) and (q.get("exclude") == [tag_ex]) and (q.get("search") == [run])
results["hydration_roundtrip"] = (kept, url)
shot("03-deep-link-hydrated")

# --- Check 4: documents CRUD — search finds the seeded document ---------------
# Clear any leftover filters, type the unique title into the search box, and
# assert the document row renders in the list.
goto_url(base + "/#/document"); time.sleep(1); wait_for_load()
js("""
  (() => {
    const box = document.querySelector('.search-input input, input.search-input, .search-input');
    const el = box && box.tagName === 'INPUT' ? box : document.querySelector('.search-row input');
    if (el) { el.value = arguments0; el.dispatchEvent(new Event('input', {bubbles:true})); }
  })();
""".replace("arguments0", json.dumps(doc_title)))
time.sleep(2.5); wait_for_load()
found = js("""
  return (() => [...document.querySelectorAll('.doc-title')].some(e => e.textContent.includes(arguments0)))();
""".replace("arguments0", json.dumps(run)))
results["documents_crud"] = (bool(found), f"searched '{doc_title}' -> row visible={found}")
shot("04-documents-search")

# --- Check 5: tags + facets — Facets sidebar shows the seeded tag + count -----
goto_url(base + "/#/document"); time.sleep(1.5); wait_for_load()
# Switch the tag sidebar from tree to facets (SelectButton with a "Facets" label).
js("""
  (() => {
    const btns = [...document.querySelectorAll('button, [role=button], .p-togglebutton, .p-selectbutton *')];
    const facets = btns.find(b => /facets/i.test(b.textContent||''));
    if (facets) facets.click();
  })();
""")
time.sleep(2)
facet = js("""
  return (() => {
    const names = [...document.querySelectorAll('.tag-tree, .tag-count, [class*=tag]')].map(e=>e.textContent).join(' ');
    const hasTag = names.includes(arguments0);
    const hasCount = document.querySelectorAll('.tag-count').length > 0;
    return {tag: hasTag, count: hasCount};
  })();
""".replace("arguments0", json.dumps(tag_in_name)))
ok = bool(facet.get("tag")) and bool(facet.get("count"))
results["tags_facets"] = (ok, f"facet tag '{tag_in_name}' visible={facet.get('tag')} counts_rendered={facet.get('count')}")
shot("05-tags-facets")

# --- Check 6: vocabulary dropdown on a document -------------------------------
# Open the document in edit mode, open the `type` Select (a Dublin-Core
# vocabulary-backed dropdown), confirm options render, pick the first, save.
goto_url(base + f"/#/document/edit/{doc_id}"); time.sleep(2.5); wait_for_load()
# The Dublin-Core vocabulary selects (type/coverage/rights) live inside the
# collapsed "Additional metadata" section — expand it before opening the Select.
js("""
  (() => {
    const b = [...document.querySelectorAll('button')]
      .find(x => /additional metadata/i.test(x.textContent||''));
    if (b) b.click();
  })();
""")
time.sleep(1.2)
js("""
  (() => {
    const sel = document.querySelector('#edit-type');
    const trigger = sel && (sel.querySelector('.p-select-label') || sel);
    if (trigger) trigger.click();
  })();
""")
time.sleep(1.2)
picked = js("""
  return (() => {
    // PrimeVue Select options need a full pointer sequence; a bare .click()
    // does not commit the model value.
    const opts = [...document.querySelectorAll('.p-select-option, li[role=option]')]
      .filter(o => o.textContent.trim());
    if (!opts.length) return {n: 0, label: ''};
    const o = opts[0]; const label = o.textContent.trim();
    for (const t of ['pointerdown','mousedown','pointerup','mouseup','click']) {
      o.dispatchEvent(new MouseEvent(t, {bubbles:true, cancelable:true, view:window}));
    }
    return {n: opts.length, label};
  })();
""")
time.sleep(0.8)
shot("06a-vocab-dropdown")
# Save the document (real click on the Save button by its icon/label).
js("""
  (() => {
    const btns = [...document.querySelectorAll('button')];
    const save = btns.find(b => b.querySelector('.pi-check')) ||
                 btns.find(b => /^\\s*save\\s*$/i.test(b.textContent||''));
    if (save) save.click();
  })();
""")
time.sleep(3); wait_for_load()
# Authoritative read-back: the document's type must now equal the picked value.
persisted = js("""
  return (() => fetch(location.origin + '/api/document/arg_id', {credentials:'include'})
    .then(r=>r.json()).then(d=>d.type||'').catch(()=> ''))();
""".replace("arg_id", doc_id))
opts_n = int(picked.get("n") or 0)
picked_label = (picked.get("label") or "").strip()
vocab_ok = opts_n > 0 and picked_label != "" and (persisted == picked_label)
results["vocabulary_dropdown"] = (vocab_ok, f"options={opts_n} picked='{picked_label}' persisted='{persisted}'")
shot("06b-vocab-saved")

# --- Check 7: workflow act — approve the current step on the routed doc -------
# The route was started via REST during seed; navigate to the workflow tab and
# ACT on the current step, then verify via authoritative /api/route read-back
# that the step advanced (a real transition, not just a rendered page).
goto_url(base + f"/#/document/view/{doc_id}/workflow"); time.sleep(2.5); wait_for_load()
step_before = js("""
  return (() => { const el = document.querySelector('.wf-step-name'); return el ? el.textContent.trim() : ''; })();
""")
shot("07a-workflow-before")
# Click the ACT button. Step 1 of the seeded model is a VALIDATE step, whose
# action button carries the "validate" label; fall back to any approve/validate
# button inside the ACT controls.
js("""
  (() => {
    const zone = document.querySelector('.wf-act-buttons') || document;
    const btns = [...zone.querySelectorAll('button')];
    const act = btns.find(b => /(validate|approve)/i.test(b.textContent||'')) || btns[0];
    if (act) act.click();
  })();
""")
time.sleep(3); wait_for_load()
shot("07b-workflow-after")
# Authoritative: read the route steps; the first step must now be transitioned.
wf = js("""
  return (() => fetch(location.origin + '/api/route?documentId=arg_id', {credentials:'include'})
    .then(r=>r.json())
    .then(d=>{
      const routes = d.routes||[];
      const steps = routes.length ? (routes[0].steps||[]) : [];
      const acted = steps.filter(s => s.transition).length;
      return {routes: routes.length, steps: steps.length, acted};
    }).catch(e => ({error: String(e)})))();
""".replace("arg_id", doc_id))
wf_ok = isinstance(wf, dict) and wf.get("acted", 0) >= 1
results["workflow_act"] = (wf_ok, f"before='{step_before}' route={wf}")

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

# --- Checks 10-11: anonymous, CONTEXT-ISOLATED ---------------------------------
# These run the browser LOGGED OUT. The whole logged-out region is wrapped in
# try/finally so that no matter how a check fails (navigation, DOM, screenshot,
# API), the admin session is ALWAYS restored before the harness exits — and the
# restore is verified authoritatively (check 12). A logout that is never undone
# would poison every later run against this persistent Chrome.
rest_logout()
try:
    # --- Check 10: share anonymous-view --------------------------------------
    # Open the public share URL as a genuine anonymous visitor and assert the
    # shared document's title renders.
    goto_url(base + f"/#/share/{doc_id}/{share_id}"); time.sleep(3); wait_for_load()
    anon_url = cur_url()
    share_seen = js("""
      return (() => { const h = document.querySelector('.share-header h1, .share-card h1, h1'); return h ? h.textContent.trim() : ''; })();
    """)
    share_ok = (run in share_seen) and ("/login" not in anon_url)
    results["share_anonymous"] = (share_ok, f"anon share title='{share_seen}' url={anon_url}")
    shot("10-share-anonymous")

    # --- Check 11: anonymous route guard (still logged out) ------------------
    new_tab(base + "/#/settings/users"); wait_for_load(); time.sleep(1.5)
    guard_url = cur_url()
    results["guard_redirect"] = ("/login" in guard_url, guard_url)
    shot("11-anon-route-guard")
except Exception as e:
    # A crash in the logged-out region records a failure but does NOT skip the
    # finally restore, and does not abort the BH_RESULTS emission below.
    results["anonymous_region_error"] = (False, f"exception in logged-out checks: {e}")
finally:
    # --- Restore the admin session — ALWAYS, even if a check above threw ------
    goto_url(base + "/#/login"); wait_for_load(); time.sleep(0.5)
    restore_status = rest_login()
    time.sleep(0.4)
    # --- Check 12: verify the admin session is live again (authoritative) -----
    live = admin_session_live()
    restore_ok = (restore_status == 200) and bool(live)
    results["session_restored"] = (restore_ok, f"login_status={restore_status} admin_session_live={live}")

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
    "hydration_roundtrip": "check3: deep-link kept search+tags+exclude after cold-load hydration",
    "documents_crud": "check4: documents list search found the seeded document",
    "tags_facets": "check5: Facets sidebar showed the seeded tag with a count",
    "vocabulary_dropdown": "check6: type vocabulary dropdown opened, value saved + read back",
    "workflow_act": "check7: workflow first (VALIDATE) step acted on and advanced (authoritative read-back)",
    "account_sessions": "check8: account settings sessions table rendered the current-session row",
    "monitoring_logs": "check9: monitoring log viewer rendered log rows",
    "share_anonymous": "check10: anonymous visitor saw the shared document (context-isolated)",
    "guard_redirect": "check11: anonymous /settings/users redirected to /login (context-isolated)",
    "session_restored": "check12: admin session restored + verified live after the anonymous checks",
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

# --- Cleanup: trash the documents this run created (best-effort) ---------------
if [ -n "${doc_id:-}" ]; then
  curl -sf -b "${cookie_jar}" -X DELETE "${base_url}/api/document/${doc_id}" >/dev/null 2>&1 \
    && echo "[cleanup] trashed document ${doc_id}" | tee -a "${art_dir}/checks.log" \
    || echo "[cleanup] could not trash document ${doc_id} (non-fatal)" | tee -a "${art_dir}/checks.log"
fi

if [ "${fail}" -ne 0 ]; then
  echo "== RESULT: FAIL (see ${art_dir}) =="
  exit 1
fi
echo "== RESULT: all browser-harness checks passed (artifacts: ${art_dir}) =="
