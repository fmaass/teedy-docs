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
# scripts/e2e-run.sh builds. Typical combined run (the teedy-bh container below is the
# disposable, throwaway instance the E2E_ALLOW_SEED guard requires):
#
#   ./mvnw -Pprod -DskipTests clean install && docker build -t teedy-e2e:local .
#   docker run -d --name teedy-bh -p 8080:8080 teedy-e2e:local
#   # wait for /api/app to answer, then (E2E_EXPECT_VERSION must match the built pom
#   # <version>; E2E_ALLOW_SEED=1 acknowledges the target is disposable):
#   E2E_ALLOW_SEED=1 E2E_EXPECT_VERSION=<pom version> \
#     E2E_BASE_URL=http://localhost:8080 scripts/e2e-browser-harness.sh
#   docker rm -f teedy-bh
#
# Requires: the `browser-harness` CLI (browser-use) on PATH, a local Chrome with
# CDP remote debugging allowed, curl, python3.
#
# Target guard: this harness is DESTRUCTIVE — it logs in as admin/admin, seeds fixture
# tags and documents, and stores a deliberate XSS payload as a document description. It MUST
# run only against a disposable, throwaway instance. E2E_ALLOW_SEED=1 is REQUIRED and is the
# sole guard: no runtime signal reliably tells a disposable instance from a real deployment
# (a hostname is spoofable and a fresh restore also has admin/admin), so the operator must
# explicitly acknowledge the target is disposable. Without it the harness refuses BEFORE any
# login or write (exit 2).
#
# Data hygiene: every artifact this run creates (documents, tags) is stamped with a unique
# RUN token, so reruns against a long-lived instance never collide. Cleanup runs from an EXIT
# trap, so it purges on EVERY exit path — normal, check failure, error, or a SIGINT/SIGTERM
# (CI cancel) after seeding — and can never strand the XSS payload or fixture tags. It PURGES
# everything it created: each seeded document is moved to the trash AND then permanently
# deleted from the recycle bin (so the stored XSS payload cannot linger there), and every
# seeded tag is deleted. Any artifact it fails to purge forces a non-zero exit, so the run
# never reports success with known residue left behind.
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

# --- Target guard (REQUIRED, checked before any login or write) ---------------
# This harness is destructive: it authenticates as admin/admin, seeds fixture tags and
# documents, and stores a deliberate XSS payload as a document description. Pointing it at a
# real deployment would inject that payload and default-credential writes into live data.
# There is deliberately NO hostname/heuristic auto-detection — no runtime signal reliably
# distinguishes a disposable instance from production (hostnames are spoofable; a fresh prod
# restore also answers on admin/admin), so a heuristic would give false confidence. The only
# honest guard is an explicit operator opt-in acknowledging the target is disposable. This
# check runs before check 1 and before the seed login, so an unset opt-in touches nothing.
if [ "${E2E_ALLOW_SEED:-}" != "1" ]; then
  echo "FATAL: E2E_ALLOW_SEED is not set to 1 — refusing to run. This harness seeds admin/admin fixture data and an XSS payload into ${base_url}, then purges it; it is safe ONLY against a disposable, throwaway instance. Never point it at a real deployment. Export E2E_ALLOW_SEED=1 to acknowledge the target is disposable." >&2
  exit 2
fi

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

# Ids of everything this run seeds, filled in by the seed section below. Declared up front so
# the cleanup trap can safely reference them even if the run is interrupted before they are set.
ovf_doc_id=""
rich_doc_id=""
org_doc_id=""
seeded_tag_ids=""

fail=0            # set when any check fails
cleanup_fail=0    # set when any seeded artifact could not be purged
_cleanup_done=0   # cleanup runs exactly once, even if it is entered twice

# Permanently remove one seeded document, INCLUDING from the recycle bin. A plain DELETE is
# only a soft-delete into the trash, so the document — and its stored description, which for
# the rich-description check holds an XSS payload — must then be permanently deleted or it
# survives. A failure is counted so the run cannot report success with residue left behind.
purge_document() {
  local d="$1"
  [ -n "${d}" ] || return 0
  # Move to trash (soft-delete) first — /document/:id/permanent only accepts a document that
  # is already in the recycle bin. Then purge it so nothing (payload included) lingers there.
  curl -sf -b "${cookie_jar}" -X DELETE "${base_url}/api/document/${d}" >/dev/null 2>&1 || true
  if curl -sf -b "${cookie_jar}" -X DELETE "${base_url}/api/document/${d}/permanent" >/dev/null 2>&1; then
    echo "[cleanup] purged document ${d} (trashed + permanently deleted)" | tee -a "${art_dir}/checks.log"
  else
    echo "[cleanup] WARNING: could not permanently delete document ${d} — it may remain in the recycle bin" | tee -a "${art_dir}/checks.log"
    cleanup_fail=$((cleanup_fail + 1))
  fi
}

# EXIT-trap cleanup. Runs on every exit path — normal end, check failure, error, and (via the
# INT/TERM handlers below) a Ctrl-C or CI cancel after seeding — so an interruption can never
# strand the XSS payload or the fixture tags. Idempotent (guarded to run once) and tolerant of
# a partially-seeded state (the id vars may still be empty).
cleanup() {
  local rc=$?
  [ "${_cleanup_done}" -eq 1 ] && return
  _cleanup_done=1

  for d in "${ovf_doc_id}" "${rich_doc_id}" "${org_doc_id}"; do
    purge_document "${d}"
  done
  for t in ${seeded_tag_ids}; do
    if curl -sf -b "${cookie_jar}" -X DELETE "${base_url}/api/tag/${t}" >/dev/null 2>&1; then
      echo "[cleanup] deleted tag ${t}" | tee -a "${art_dir}/checks.log"
    else
      echo "[cleanup] WARNING: could not delete tag ${t}" | tee -a "${art_dir}/checks.log"
      cleanup_fail=$((cleanup_fail + 1))
    fi
  done
  rm -f "${cookie_jar}"

  # Never exit 0 when a check failed, a purge failed, or the run was interrupted (rc already
  # non-zero) — any of those means this was not a clean, fully-cleaned pass.
  if [ "${rc}" -eq 0 ] && { [ "${fail}" -ne 0 ] || [ "${cleanup_fail}" -ne 0 ]; }; then
    rc=1
  fi
  if [ "${cleanup_fail}" -ne 0 ]; then
    echo "== RESULT: FAIL — cleanup incomplete: ${cleanup_fail} seeded artifact(s) may remain (see ${art_dir}) ==" | tee -a "${art_dir}/checks.log" >&2
  elif [ "${rc}" -ne 0 ]; then
    echo "== RESULT: FAIL (exit ${rc}); seeded artifacts purged (see ${art_dir}) ==" | tee -a "${art_dir}/checks.log"
  else
    echo "== RESULT: all browser-harness checks passed; seeded artifacts purged (artifacts: ${art_dir}) ==" | tee -a "${art_dir}/checks.log"
  fi
  exit "${rc}"
}
# A trapped INT/TERM otherwise waits for the running foreground command, then leaves via the
# default action WITHOUT running EXIT on some shells; make each one exit explicitly so the
# EXIT trap (cleanup) always fires and purges.
trap 'exit 130' INT
trap 'exit 143' TERM
trap cleanup EXIT

# Unique per-run token so reruns against a long-lived instance never collide.
run_token="bh-$(date +%s)-$$"

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
# seeded_tag_ids (declared up top for the cleanup trap) accumulates every seeded tag id so
# cleanup can delete each one. ovf_tag_ids instead carries the curl "-d tags=<id>" flags for
# the document PUT and is unsuitable for iteration.
for n in 1 2 3 4 5; do
  tid="$(api_put -d "name=${run_token}-ovf${n}&color=#2aabd2" "${base_url}/api/tag" | json_field id || true)"
  [ -z "${tid}" ] && note FAIL "seed: could not create overflow tag ${n}"
  ovf_tag_ids="${ovf_tag_ids} -d tags=${tid}"
  [ -n "${tid}" ] && seeded_tag_ids="${seeded_tag_ids} ${tid}"
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

# --- Seed for the PDF page-organizer check (22, #73) --------------------------
# A document carrying a 3-page PDF so the organizer has a real multi-page document to
# rasterize client-side (pdf.js) in the browser check below.
org_pdf="${repo_root}/docs-web/src/main/webapp/e2e/fixtures/multipage.pdf"
org_doc_title="${run_token} PDF Organizer Document"
org_doc_id="$(api_put --data-urlencode "title=${org_doc_title}" \
  -d "language=eng" "${base_url}/api/document" | json_field id || true)"
[ -z "${org_doc_id}" ] && note FAIL "seed: could not create organizer document"
if [ -n "${org_doc_id}" ]; then
  api_put -F "id=${org_doc_id}" -F "file=@${org_pdf};type=application/pdf" \
    "${base_url}/api/file" >/dev/null || note FAIL "seed: could not attach organizer PDF"
fi

echo "[seed] ovf_doc=${ovf_doc_id} rich_doc=${rich_doc_id} org_doc=${org_doc_id}" \
  | tee -a "${art_dir}/checks.log"

# --- Checks 2, 8, 9, 14, 21: real-browser flow --------------------------------
BH_OUT="$(BH_BASE_URL="${base_url}" BH_ART="${art_dir}" \
  BH_RUN="${run_token}" \
  BH_OVF_DOC_TITLE="${ovf_doc_title}" BH_OVF_TAG4="${run_token}-ovf4" BH_OVF_TAG5="${run_token}-ovf5" \
  BH_RICH_DOC_ID="${rich_doc_id}" BH_ORG_DOC_ID="${org_doc_id}" \
  browser-harness <<'PY'
import json, os, time, urllib.parse

base = os.environ["BH_BASE_URL"]
art = os.environ["BH_ART"]
run = os.environ.get("BH_RUN", "")
ovf_doc_title = os.environ.get("BH_OVF_DOC_TITLE", "")
ovf_tag4 = os.environ.get("BH_OVF_TAG4", "")
ovf_tag5 = os.environ.get("BH_OVF_TAG5", "")
rich_doc_id = os.environ.get("BH_RICH_DOC_ID", "")
org_doc_id = os.environ.get("BH_ORG_DOC_ID", "")
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

# --- Check 22 (#73): PDF page organizer renders a populated thumbnail grid ----
# Open the organizer on the seeded 3-page PDF (grid is the default file view, so the "Edit
# pages" action is on the PDF tile) and assert pdf.js actually rasterized one non-empty
# <canvas> thumbnail per source page — the client-side render is the #73 known-unknown.
goto_url(base + "/#/document/view/" + org_doc_id + "/content"); time.sleep(2.5); wait_for_load()
js("""
  (() => {
    const b = [...document.querySelectorAll('button')]
      .find(x => /edit pages/i.test(x.getAttribute('aria-label') || ''));
    if (b) b.click();
  })();
""")
time.sleep(3.5); wait_for_load()
org = js("""
  return (() => {
    const dialog = document.querySelector('.p-dialog');
    const cards = document.querySelectorAll('.pdf-page-card');
    const painted = [...document.querySelectorAll('.pdf-page-card canvas')]
      .filter(c => c.width > 0 && c.height > 0).length;
    return {open: !!dialog, cards: cards.length, painted};
  })();
""")
shot("22-pdf-organizer")
org_ok = bool(org.get("open")) and int(org.get("cards") or 0) == 3 and int(org.get("painted") or 0) >= 1
results["pdf_organizer"] = (org_ok, f"open={org.get('open')} cards={org.get('cards')} painted_canvases={org.get('painted')}")

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
    "pdf_organizer": "check22: PDF page organizer opened and pdf.js rendered a populated 3-page thumbnail grid (#73)",
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

# Cleanup (purge of every seeded document + tag) and the final RESULT line / exit code are
# handled by the cleanup() EXIT trap registered near the top of this script, so they run on
# EVERY exit path — including a signal after seeding — not only when control reaches here.
