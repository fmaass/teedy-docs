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
# 3.5.0). A mismatch FAILS the harness so it can never certify a stale image.
#
# Checks performed (each FAIL exits nonzero at the end):
#   1. /api/app answers and current_version == expected (default 3.5.1)      [api]
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
#  13. Tag pickers (v3.2.2 C) — the document-edit tag MultiSelect filter box
#      winnows options to a typed fragment AND a selected tag renders as a COLORED
#      TagBadge chip (non-transparent background), not a plain label            [browser]
#  14. Tag overflow (v3.2.2 D) — a document with 5 tags shows a focusable "+2"
#      control whose popover (teleported to <body>) reveals the 4th/5th tag names
#      and is NOT clipped (rect inside the viewport) — a real-Chrome layout check
#      with a CDP screenshot; activating it does not navigate the row           [browser]
#  15. Double-click open (v3.2.2 D) — double-clicking a document row navigates to
#      the full /#/document/view/:id (single-click only opens the slide-over)   [browser]
#  10. Share anonymous-view (context-isolated) — after logout, the public
#      /#/share/:doc/:share URL renders the shared document's title to an
#      anonymous visitor                                                        [browser]
#  11. Anonymous route guard (context-isolated) — after logout, a deep link to
#      /#/settings/users redirects to /login                                    [browser]
#  16. Disabled-user login (v3.2.2 B, context-isolated) — a user disabled via the
#      admin API is DENIED native form login (stays on /login, session stays
#      anonymous via GET /api/user)                                             [browser+api]
#  17. TOTP login (v3.2.2 A, context-isolated) — a TOTP-enabled user's password
#      submit reveals the OTP field (400 ValidationCodeRequired); a code computed
#      with the same RFC-6238 algorithm the server verifies completes the login
#      (session live via GET /api/user) — a genuine end-to-end 2FA login        [browser+api]
#  18. Favorites (v3.5.0 #41) — click the real FavoriteStar on a document row, then
#      confirm via authoritative /api/favorite + /api/document read-back: doc is
#      favorite:true and in /document/list?favorites=me; repeat PUT is an idempotent
#      200; DELETE removes it from the favorites list                           [browser+api]
#  19. Gallery view (v3.5.0 #39) — toggle the doc list to Gallery, assert a gallery
#      card renders for the seeded doc, assert the mode persists in localStorage
#      teedy_document_view_mode across a reload, then switch back to List        [browser]
#  20. Stats dashboard (v3.5.0 #40) — admin #/settings/stats renders the five totals
#      cards + a chart <canvas> + a per-user storage row; switching the window (7->30)
#      re-hits /api/app/stats; and (context-isolated) a NON-admin gets 403 from
#      GET /api/app/stats?window=7, admin restored after                        [browser+api]
#  21. Rich description (v3.5.0 #38, security-critical) — POST /document/:id with a
#      description mixing legitimate formatting and a hostile payload; the stored HTML
#      read back via /api/document is inert (no <script/onerror/javascript:) while the
#      <strong>/<a href="https:// survived; the Quill editor mounts on the edit view [browser+api]
#  22. Persisted rotation (v3.5.2 #35) — upload an image, rotate it 90° (real UI
#      "Rotate right" control, or a credentialed POST /api/file/:id/rotation from the
#      page), then RELOAD and assert GET /api/document/:id?files=true reports the file's
#      rotation==90 — the authoritative read-back proving the value is server-persisted
#      (not transient); a failed or non-persisted rotation FAILS the check         [browser+api]
#  12. Session restore (verified) — after the logged-out checks 10-11 + 16-17, the
#      admin session is restored and confirmed live via GET /api/user
#      (username=admin, not anonymous). Those checks run inside try/finally so this
#      restore ALWAYS runs, even if one of them throws                          [browser+api]
#
# Every browser check performs a real interaction (search / click / open / act)
# plus an assertion on rendered DOM or authoritative API state, and saves a
# screenshot. No check merely loads a page and claims coverage.
#
# Artifacts: e2e-artifacts/browser-harness/*.png + checks.log (gitignored dir).
set -uo pipefail

base_url="${E2E_BASE_URL:-http://localhost:8080}"
expect_version="${E2E_EXPECT_VERSION:-3.5.1}"
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

# --- Seed for the v3.2.2 behavior checks (A/B/C/D) ----------------------------
# D (#24): a document carrying FIVE tags so the list row deterministically
# overflows (>3) and renders the "+2" TagOverflow control the popover check drives.
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

# B (#17): a normal user that admin will DISABLE, then confirm login is denied.
dis_user="${run_token}dis"
dis_pass="DisablePass123"
api_put -d "username=${dis_user}" -d "password=${dis_pass}" \
  -d "email=${dis_user}@example.com" -d "storage_quota=1000000000" \
  "${base_url}/api/user" >/dev/null || note FAIL "seed: could not create disable-target user"
# Disable it via the admin API (the SettingsUsers toggle posts the same param).
api_post -d "disabled=true" "${base_url}/api/user/${dis_user}" >/dev/null \
  || note FAIL "seed: could not disable target user"

# A (#18): a TOTP-enabled user. Create it, log in AS it to enable TOTP (the secret
# is bound to that account), capture the Base32 secret so the browser can compute a
# valid code with the SAME RFC-6238 algorithm the server verifies.
totp_user="${run_token}totp"
totp_pass="TotpPass123"
api_put -d "username=${totp_user}" -d "password=${totp_pass}" \
  -d "email=${totp_user}@example.com" -d "storage_quota=1000000000" \
  "${base_url}/api/user" >/dev/null || note FAIL "seed: could not create TOTP user"
totp_jar="$(mktemp)"
curl -sf -c "${totp_jar}" -X POST -d "username=${totp_user}&password=${totp_pass}&remember=false" \
  "${base_url}/api/user/login" >/dev/null || note FAIL "seed: TOTP user login failed"
totp_secret="$(curl -sf -b "${totp_jar}" -X POST "${base_url}/api/user/enable_totp" | json_field secret || true)"
rm -f "${totp_jar}"
[ -z "${totp_secret}" ] && note FAIL "seed: could not enable TOTP / read secret"

# --- Seed for the v3.5.0 feature checks (favorites / gallery / stats / rich desc) --
# Stats (#40): a plain NON-admin user. GET /api/app/stats is admin-gated (403 for a
# non-admin), so the stats check logs in AS this user to prove the 403, then restores
# admin. Created here (not reusing dis_user, which is disabled and cannot log in).
stats_user="${run_token}nonadmin"
stats_pass="NonAdminPass123"
api_put -d "username=${stats_user}" -d "password=${stats_pass}" \
  -d "email=${stats_user}@example.com" -d "storage_quota=1000000000" \
  "${base_url}/api/user" >/dev/null || note FAIL "seed: could not create non-admin stats user"

# Favorites (#41): a dedicated RUN-stamped document the favorites check stars/unstars
# via the real UI star + authoritative /api/favorite read-back. Kept separate from the
# main probe doc so its favorite state does not perturb the other checks.
fav_doc_title="${run_token} Favorite Document"
fav_doc_id="$(api_put --data-urlencode "title=${fav_doc_title}" \
  --data-urlencode "description=browser-harness ${run_token}" \
  -d "language=eng" "${base_url}/api/document" | json_field id || true)"
[ -z "${fav_doc_id}" ] && note FAIL "seed: could not create favorite-target document"

# Rich description (#38): a RUN-stamped document the sanitizer check updates with a
# mixed legitimate+hostile description, then reads back to assert the XSS payload was
# stripped and the legitimate formatting survived.
rich_doc_title="${run_token} Rich Description Document"
rich_doc_id="$(api_put --data-urlencode "title=${rich_doc_title}" \
  -d "language=eng" "${base_url}/api/document" | json_field id || true)"
[ -z "${rich_doc_id}" ] && note FAIL "seed: could not create rich-description document"

# --- Seed for the v3.5.2 persisted-rotation check (#35) -----------------------
# Rotation is a PER-FILE property, so the check needs a document with a real, rotatable
# IMAGE file. Create a RUN-stamped document, then upload wide.png (60x20 — the same
# committed fixture the Playwright rotation.spec.ts uses) to it via multipart PUT /api/file
# (form field `id`=documentId, `file`=image bytes), authenticated with the same admin
# session cookie the other seeds use. The upload response returns the new file id.
rot_doc_title="${run_token} Rotation Document"
rot_doc_id="$(api_put --data-urlencode "title=${rot_doc_title}" \
  -d "language=eng" "${base_url}/api/document" | json_field id || true)"
[ -z "${rot_doc_id}" ] && note FAIL "seed: could not create rotation image doc"
rot_fixture="${repo_root}/docs-web/src/main/webapp/e2e/fixtures/wide.png"
rot_file_id=""
if [ -n "${rot_doc_id}" ] && [ -f "${rot_fixture}" ]; then
  rot_file_id="$(curl -sf -b "${cookie_jar}" -X PUT \
    -F "id=${rot_doc_id}" -F "file=@${rot_fixture};type=image/png" \
    "${base_url}/api/file" | json_field id || true)"
fi
# Fall back to the authoritative file id if the upload response id was not captured.
[ -z "${rot_file_id}" ] && [ -n "${rot_doc_id}" ] && rot_file_id="$(curl -sf -b "${cookie_jar}" \
  "${base_url}/api/document/${rot_doc_id}?files=true" \
  | python3 -c 'import sys,json;f=json.load(sys.stdin).get("files",[]);print(f[0]["id"] if f else "")' 2>/dev/null || true)"
[ -z "${rot_file_id}" ] && note FAIL "seed: could not create rotation image doc"

echo "[seed] doc=${doc_id} tag_in=${tag_in} tag_ex=${tag_ex} share=${share_id} route_started=${route_ok:-0}" \
  | tee -a "${art_dir}/checks.log"
echo "[seed] ovf_doc=${ovf_doc_id} dis_user=${dis_user} totp_user=${totp_user} totp_secret_len=${#totp_secret}" \
  | tee -a "${art_dir}/checks.log"
echo "[seed] stats_user=${stats_user} fav_doc=${fav_doc_id} rich_doc=${rich_doc_id}" \
  | tee -a "${art_dir}/checks.log"
echo "[seed] rot_doc=${rot_doc_id} rot_file=${rot_file_id}" \
  | tee -a "${art_dir}/checks.log"

# --- Checks 2-17: real-browser flow -------------------------------------------
BH_OUT="$(BH_BASE_URL="${base_url}" BH_ART="${art_dir}" \
  BH_TAG_IN="${tag_in}" BH_TAG_EX="${tag_ex}" BH_TAG_IN_NAME="${run_token}-in" \
  BH_DOC_ID="${doc_id}" BH_DOC_TITLE="${doc_title}" BH_SHARE_ID="${share_id}" \
  BH_RUN="${run_token}" \
  BH_OVF_DOC_TITLE="${ovf_doc_title}" BH_OVF_TAG4="${run_token}-ovf4" BH_OVF_TAG5="${run_token}-ovf5" \
  BH_DIS_USER="${dis_user}" BH_DIS_PASS="${dis_pass}" \
  BH_TOTP_USER="${totp_user}" BH_TOTP_PASS="${totp_pass}" BH_TOTP_SECRET="${totp_secret}" \
  BH_STATS_USER="${stats_user}" BH_STATS_PASS="${stats_pass}" \
  BH_FAV_DOC_ID="${fav_doc_id}" BH_FAV_DOC_TITLE="${fav_doc_title}" \
  BH_RICH_DOC_ID="${rich_doc_id}" \
  BH_ROT_DOC_ID="${rot_doc_id}" BH_ROT_FILE_ID="${rot_file_id}" BH_ROT_DOC_TITLE="${rot_doc_title}" \
  browser-harness <<'PY'
import json, os, time, urllib.parse, hmac, hashlib, base64, struct

base = os.environ["BH_BASE_URL"]
art = os.environ["BH_ART"]
tag_in = os.environ.get("BH_TAG_IN", "")
tag_ex = os.environ.get("BH_TAG_EX", "")
tag_in_name = os.environ.get("BH_TAG_IN_NAME", "")
doc_id = os.environ.get("BH_DOC_ID", "")
doc_title = os.environ.get("BH_DOC_TITLE", "")
share_id = os.environ.get("BH_SHARE_ID", "")
run = os.environ.get("BH_RUN", "")
ovf_doc_title = os.environ.get("BH_OVF_DOC_TITLE", "")
ovf_tag4 = os.environ.get("BH_OVF_TAG4", "")
ovf_tag5 = os.environ.get("BH_OVF_TAG5", "")
dis_user = os.environ.get("BH_DIS_USER", "")
dis_pass = os.environ.get("BH_DIS_PASS", "")
totp_user = os.environ.get("BH_TOTP_USER", "")
totp_pass = os.environ.get("BH_TOTP_PASS", "")
totp_secret = os.environ.get("BH_TOTP_SECRET", "")
stats_user = os.environ.get("BH_STATS_USER", "")
stats_pass = os.environ.get("BH_STATS_PASS", "")
fav_doc_id = os.environ.get("BH_FAV_DOC_ID", "")
fav_doc_title = os.environ.get("BH_FAV_DOC_TITLE", "")
rich_doc_id = os.environ.get("BH_RICH_DOC_ID", "")
rot_doc_id = os.environ.get("BH_ROT_DOC_ID", "")
rot_file_id = os.environ.get("BH_ROT_FILE_ID", "")
rot_doc_title = os.environ.get("BH_ROT_DOC_TITLE", "")
results = {}

# RFC-6238 TOTP: recompute the SAME 6-digit code the server verifies (Base32 secret,
# 30s window, HmacSHA1). A mock would pass against a broken server — this does not.
def totp_now(secret):
    key = base64.b32decode(secret.upper() + "=" * ((8 - len(secret) % 8) % 8))
    counter = int(time.time()) // 30
    msg = struct.pack(">Q", counter)
    h = hmac.new(key, msg, hashlib.sha1).digest()
    off = h[-1] & 0x0F
    binary = ((h[off] & 0x7F) << 24) | ((h[off+1] & 0xFF) << 16) | ((h[off+2] & 0xFF) << 8) | (h[off+3] & 0xFF)
    return str(binary % 1_000_000).zfill(6)

def shot(name):
    capture_screenshot(path=os.path.join(art, name + ".png"))

def cur_url():
    return page_info()["url"]

# --- index-latency helper: a freshly-created document is not immediately searchable
# (Lucene indexing is asynchronous), so the list row a UI check depends on can be
# missing for a beat. Poll the authoritative /api/document/list?search=<title> as the
# current (credentialed) session until the document appears, then land the browser on
# the filtered list so the row is actually rendered. Returns True once the doc is both
# searchable AND its row is on the page. Bounded by timeout_s so a genuinely-missing
# doc still fails the caller's assertion rather than hanging.
def wait_for_doc_searchable(title, timeout_s=15):
    deadline = time.time() + timeout_s
    api_found = False
    while time.time() < deadline:
        api_found = js("""
          return (() => fetch(location.origin + '/api/document/list?limit=50&search='
              + encodeURIComponent(arg_title), {credentials:'include'})
            .then(r => r.ok ? r.json() : {documents: []})
            .then(d => (d.documents||[]).some(x => (x.title||'').includes(arg_title)))
            .catch(() => false))();
        """.replace("arg_title", json.dumps(title)))
        if api_found:
            break
        time.sleep(1.0)
    # Land on the filtered list and wait for the row to actually render.
    goto_url(base + "/#/document?search=" + urllib.parse.quote(title))
    time.sleep(1.5); wait_for_load()
    row_deadline = time.time() + 6
    row_seen = False
    while time.time() < row_deadline:
        row_seen = js("""
          return (() => [...document.querySelectorAll('.doc-title')]
            .some(e => (e.textContent||'').includes(arg_title)))();
        """.replace("arg_title", json.dumps(title)))
        if row_seen:
            break
        time.sleep(0.8)
    return bool(api_found and row_seen)

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
# Wait out the async Lucene indexing latency (a freshly-created doc is not instantly
# searchable), which lands the browser on the filtered list; then assert the row renders.
wait_for_doc_searchable(doc_title)
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

# --- Check 13 (behavior C): doc-edit tag MultiSelect filter + colored chip -----
# Open the document-edit MultiSelect, type the include-tag name into the FILTER box,
# assert the option list winnows to it, pick it, and assert the selected chip is a
# COLORED TagBadge (span.teedy-tag with a non-transparent background) — not a plain
# label. Reverting the #14/#23 filter box or the #chip slot would fail this.
# The seed document is tagged with tag_in, so document-edit renders that selection
# through the MultiSelect #chip slot on load — a COLORED TagBadge (span.teedy-tag with
# the tag's #2aabd2 background), NOT a plain PrimeVue label. Read that chip first
# (before touching the overlay), then open the overlay and prove the FILTER winnows.
goto_url(base + f"/#/document/edit/{doc_id}"); time.sleep(2.5); wait_for_load()
chip = js("""
  return (() => {
    const chips = [...document.querySelectorAll('#edit-tags .teedy-tag, .tag-multiselect .teedy-tag')]
      .filter(c => (c.textContent||'').includes(arguments0));
    if (!chips.length) return {found: false};
    return {found: true, bg: getComputedStyle(chips[0]).backgroundColor};
  })();
""".replace("arguments0", json.dumps(tag_in_name)))
shot("13a-tag-chip")
# Open the overlay and confirm the filter box is present + winnows the option list.
js("""
  (() => {
    const ms = document.querySelector('#edit-tags');
    const trigger = ms && (ms.querySelector('.p-multiselect-dropdown') || ms.querySelector('.p-multiselect-label') || ms);
    if (trigger) trigger.click();
  })();
""")
time.sleep(1.2)
filt = js("""
  return (() => !!document.querySelector('.p-multiselect-overlay input.p-multiselect-filter, .p-multiselect-overlay .p-multiselect-filter input, .p-multiselect-overlay input[role=searchbox]'))();
""")
before_n = js("return (() => document.querySelectorAll('.p-multiselect-overlay li[role=option]').length)();")
js("""
  (() => {
    const box = document.querySelector('.p-multiselect-overlay input.p-multiselect-filter, .p-multiselect-overlay .p-multiselect-filter input, .p-multiselect-overlay input[role=searchbox]');
    if (box) { box.value = arguments0; box.dispatchEvent(new Event('input', {bubbles:true})); }
  })();
""".replace("arguments0", json.dumps(tag_in_name)))
time.sleep(1.2)
after = js("""
  return (() => {
    const opts = [...document.querySelectorAll('.p-multiselect-overlay li[role=option]')];
    const matching = opts.filter(o => (o.textContent||'').includes(arguments0)).length;
    return {total: opts.length, matching};
  })();
""".replace("arguments0", json.dumps(tag_in_name)))
shot("13b-tag-filter")
opts_before = int(before_n or 0)
opts_total = int(after.get("total") or 0)
opts_match = int(after.get("matching") or 0)
chip_bg = (chip.get("bg") or "") if isinstance(chip, dict) else ""
tag_picker_ok = (
    bool(chip.get("found"))                              # selected tag rendered as a chip
    and chip_bg.startswith("rgb") and chip_bg != "rgba(0, 0, 0, 0)"  # and it is COLORED
    and bool(filt)                                       # filter box present
    and opts_before > opts_match                         # filter actually REMOVED non-matching options
    and opts_total >= 1 and opts_total == opts_match     # only the match(es) remain
)
results["tag_picker"] = (tag_picker_ok, f"chip={chip} filter_box={filt} opts_before={opts_before} opts_total={opts_total} opts_match={opts_match}")

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

# --- Check 15 (behavior D): double-click a row opens the full document view ----
# Single-click opens a slide-over; a DOUBLE-click must navigate to /document/view/<id>.
# Wait out the async index latency so the row is reliably rendered before the dblclick.
wait_for_doc_searchable(doc_title)
# Dispatch a real dblclick on the seeded document's row title cell.
js("""
  (() => {
    const cell = [...document.querySelectorAll('.doc-title')].find(e => (e.textContent||'').includes(arguments0));
    const row = cell ? cell.closest('tr') : null;
    const target = row || cell;
    if (target) target.dispatchEvent(new MouseEvent('dblclick', {bubbles:true, cancelable:true, view:window}));
  })();
""".replace("arguments0", json.dumps(run)))
time.sleep(2.5); wait_for_load()
dbl_url = cur_url()
dblclick_ok = "/document/view/" in dbl_url
results["dblclick_open"] = (dblclick_ok, f"after dblclick url={dbl_url}")
shot("15-dblclick-open")

# --- Check 18 (#41): favorites round-trip — real star click + authoritative API -
# As admin: drive the real FavoriteStar control on the seeded favorite document's list
# row, then confirm every claim against authoritative /api/favorite + /api/document
# read-back: the doc is favorite:true, it appears in GET /api/document/list?favorites=me,
# a repeat PUT is an idempotent 200, and after DELETE it is gone from the favorites list.
# Wait out the async index latency and land on the filtered list so the favorite-target
# doc's ROW (carrying the star control) is reliably rendered before we click the star.
fav_searchable = wait_for_doc_searchable(fav_doc_title)
# Click the star on the seeded document's row (the .favorite-star Button rendered in the
# star Column). Locate the row by its title cell, then its star control.
star_clicked = js("""
  return (() => {
    const cell = [...document.querySelectorAll('.doc-title')].find(e => (e.textContent||'').includes(arguments0));
    const row = cell ? cell.closest('tr') : null;
    if (!row) return {found: false};
    const star = row.querySelector('.favorite-star');
    if (!star) return {found: false};
    star.click();
    return {found: true, pressed_before: star.getAttribute('aria-pressed')};
  })();
""".replace("arguments0", json.dumps(run)))
time.sleep(1.5)
shot("18a-favorite-starred")
# Authoritative: the document now reports favorite:true.
fav_flag = js("""
  return (() => fetch(location.origin + '/api/document/arg_id', {credentials:'include'})
    .then(r=>r.json()).then(d=> d.favorite === true).catch(()=> false))();
""".replace("arg_id", fav_doc_id))
# Authoritative: the favorites=me filtered list includes this document.
fav_in_list = js("""
  return (() => fetch(location.origin + '/api/document/list?favorites=me&limit=100', {credentials:'include'})
    .then(r=>r.json())
    .then(d => (d.documents||[]).some(x => x.id === arg_id)).catch(()=> false))();
""".replace("arg_id", json.dumps(fav_doc_id)))
# Idempotent re-star: a repeat PUT must be a 200 (no error), not a duplicate/500.
repeat_put = js("""
  return (() => fetch(location.origin + '/api/favorite/arg_id', {method:'PUT', credentials:'include'})
    .then(r => r.status).catch(()=> 0))();
""".replace("arg_id", fav_doc_id))
time.sleep(0.4)
# Unstar via the API and confirm it drops out of the favorites list.
del_status = js("""
  return (() => fetch(location.origin + '/api/favorite/arg_id', {method:'DELETE', credentials:'include'})
    .then(r => r.status).catch(()=> 0))();
""".replace("arg_id", fav_doc_id))
time.sleep(0.4)
fav_gone = js("""
  return (() => fetch(location.origin + '/api/document/list?favorites=me&limit=100', {credentials:'include'})
    .then(r=>r.json())
    .then(d => !(d.documents||[]).some(x => x.id === arg_id)).catch(()=> false))();
""".replace("arg_id", json.dumps(fav_doc_id)))
favorites_ok = (
    bool(star_clicked.get("found"))
    and bool(fav_flag)                 # doc reports favorite:true after the UI click
    and bool(fav_in_list)              # and appears in favorites=me
    and int(repeat_put or 0) == 200    # repeat PUT is an idempotent 200
    and int(del_status or 0) == 200    # DELETE succeeds
    and bool(fav_gone)                 # and it is gone from favorites=me
)
results["favorites_roundtrip"] = (favorites_ok, f"searchable={fav_searchable} star={star_clicked} favorite_flag={fav_flag} in_list={fav_in_list} repeat_put={repeat_put} delete={del_status} gone={fav_gone}")
shot("18b-favorite-unstarred")

# --- Check 19 (#39): gallery view mode — toggle, card renders, persists ---------
# As admin: switch the doc-list to Gallery via the SelectButton toggle, assert a gallery
# card (article.doc-card with the seeded title) renders, assert the mode persisted to
# localStorage teedy_document_view_mode AND survived a reload, then switch back to List.
goto_url(base + "/#/document"); time.sleep(1.5); wait_for_load()
# Search so the seeded doc is among the rendered results in either mode.
js("""
  (() => {
    const el = document.querySelector('.search-input input, input.search-input, .search-row input');
    if (el) { el.value = arguments0; el.dispatchEvent(new Event('input', {bubbles:true})); }
  })();
""".replace("arguments0", json.dumps(doc_title)))
time.sleep(2.0); wait_for_load()
# Click the "Gallery" option in the view-mode SelectButton.
js("""
  (() => {
    const toggle = document.querySelector('.view-mode-toggle') || document;
    const opts = [...toggle.querySelectorAll('.p-togglebutton, [role=button], .p-selectbutton *, .view-mode-label')];
    const gallery = opts.find(o => /gallery/i.test(o.textContent||''));
    (gallery || opts[0]).click();
  })();
""")
time.sleep(1.8); wait_for_load()
gallery_state = js("""
  return (() => {
    const cards = [...document.querySelectorAll('article.doc-card')];
    const titled = cards.some(c => {
      const t = c.querySelector('.card-title');
      return t && (t.textContent||'').includes(arguments0);
    });
    return {cards: cards.length, titled, stored: localStorage.getItem('teedy_document_view_mode')};
  })();
""".replace("arguments0", json.dumps(run)))
shot("19a-gallery-cards")
# Reload to prove the mode persists across a cold load (localStorage-restored).
js("location.reload()")
time.sleep(3.5); wait_for_load()
gallery_after_reload = js("""
  return (() => ({
    cards: document.querySelectorAll('article.doc-card').length,
    stored: localStorage.getItem('teedy_document_view_mode'),
  }))();
""")
# Switch back to List so later admin checks see the default render mode.
js("""
  (() => {
    const toggle = document.querySelector('.view-mode-toggle') || document;
    const opts = [...toggle.querySelectorAll('.p-togglebutton, [role=button], .p-selectbutton *, .view-mode-label')];
    const list = opts.find(o => /(^|\\b)list(\\b|$)/i.test(o.textContent||''));
    if (list) list.click();
  })();
""")
time.sleep(1.2)
gallery_ok = (
    int(gallery_state.get("cards") or 0) >= 1
    and bool(gallery_state.get("titled"))                     # a card for the seeded doc rendered
    and gallery_state.get("stored") == "gallery"              # mode persisted to localStorage
    and int(gallery_after_reload.get("cards") or 0) >= 1      # and survived a reload as gallery
    and gallery_after_reload.get("stored") == "gallery"
)
results["gallery_view"] = (gallery_ok, f"initial={gallery_state} after_reload={gallery_after_reload}")
shot("19b-gallery-after-reload")

# --- Check 20 (#40): admin stats dashboard + non-admin 403 (context-isolated) ---
# As admin: open #/settings/stats and assert the five totals cards, a <canvas> chart, and
# a per-user storage row render; switch the window (7 -> 30) and assert the API is re-hit
# (a fresh /app/stats?window=30 response). Then, context-isolated (mirrors the guard
# checks), log in as a NON-admin and assert GET /api/app/stats?window=7 is 403; restore
# admin in a try/finally so a failure here never leaves a non-admin session behind.
goto_url(base + "/#/settings/stats"); time.sleep(3); wait_for_load()
stats_admin = js("""
  return (() => {
    const labels = [...document.querySelectorAll('.total-label')].map(e => (e.textContent||'').trim());
    const need = ['Documents', 'Users', 'Tags', 'Favorites'];
    const cards = document.querySelectorAll('.total-card').length;
    const labelsOk = need.every(n => labels.some(l => l.includes(n)))
      && labels.some(l => /files/i.test(l));   // "Files (all versions)"
    const canvases = document.querySelectorAll('canvas').length;
    const storageRows = document.querySelectorAll('.storage-table tbody tr').length;
    return {cards, labels, labelsOk, canvases, storageRows};
  })();
""")
shot("20a-stats-admin")
# Switch the window from 7 to 30 days and confirm a refetch was issued for window=30.
# The click -> query-key change -> refetch is async, so we (1) install a fetch recorder
# that logs every /app/stats URL to a global BEFORE the click, (2) click the 30-day
# window control, then (3) POLL for up to ~8s for a window=30 call to appear (a single
# post-click read races the async fetch). The recorder is restored at the end.
js("""
  (() => {
    if (!window.__statsCalls) {
      window.__statsCalls = [];
      // The app fetches via Axios (XMLHttpRequest), so wrap XHR.open; also wrap
      // fetch defensively in case any call path uses it.
      const of = window.fetch;
      window.__statsOrigFetch = of;
      window.fetch = function(...args) {
        try { const u = String(args[0]); if (u.includes('/app/stats')) window.__statsCalls.push(u); } catch (e) {}
        return of.apply(this, args);
      };
      const ox = window.XMLHttpRequest.prototype.open;
      window.__statsOrigXhrOpen = ox;
      window.XMLHttpRequest.prototype.open = function(method, url, ...rest) {
        try { const u = String(url); if (u.includes('/app/stats')) window.__statsCalls.push(u); } catch (e) {}
        return ox.call(this, method, url, ...rest);
      };
    }
  })();
""")
# Click the 30-day window option. The SelectButton renders its options as
# .p-togglebutton elements; match the one whose label reads "30".
js("""
  (() => {
    const scope = document.querySelector('.header-actions') || document;
    const opts = [...scope.querySelectorAll('.p-togglebutton, [role=button], .p-selectbutton *, button')];
    const b30 = opts.find(b => /(^|[^0-9])30([^0-9]|$)/.test((b.textContent||'').trim()));
    if (b30) b30.click();
  })();
""")
win_switched = False
win_deadline = time.time() + 8
while time.time() < win_deadline:
    win_switched = js("""
      return (() => (window.__statsCalls||[]).some(u => u.includes('window=30')))();
    """)
    if win_switched:
        break
    time.sleep(0.6)
# Restore the original fetch so the recorder does not linger for later checks.
js("""
  (() => {
    if (window.__statsOrigFetch) { window.fetch = window.__statsOrigFetch; delete window.__statsOrigFetch; }
    if (window.__statsOrigXhrOpen) { window.XMLHttpRequest.prototype.open = window.__statsOrigXhrOpen; delete window.__statsOrigXhrOpen; }
    delete window.__statsCalls;
  })();
""")
win_switched = bool(win_switched)
shot("20b-stats-window-30")
stats_dash_ok = (
    int(stats_admin.get("cards") or 0) >= 5
    and bool(stats_admin.get("labelsOk"))          # all five totals labels present
    and int(stats_admin.get("canvases") or 0) >= 1 # at least one chart canvas
    and int(stats_admin.get("storageRows") or 0) >= 1
    and bool(win_switched)                         # window switch re-hit /app/stats?window=30
)
# Non-admin 403 — context-isolated, admin restored in finally.
rest_logout()
stats_forbidden = None
stats_403_ok = False
stats_restore_ok = False
try:
    login_status = js("""
      return (() => fetch(location.origin + '/api/user/login', {
        method: 'POST', credentials: 'include',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: 'username=arg_u&password=arg_p&remember=false'
      }).then(r => r.status).catch(() => 0))();
    """.replace("arg_u", stats_user).replace("arg_p", stats_pass))
    time.sleep(0.6)
    stats_forbidden = js("""
      return (() => fetch(location.origin + '/api/app/stats?window=7', {credentials:'include'})
        .then(r => r.status).catch(() => 0))();
    """)
    stats_403_ok = (int(login_status or 0) == 200) and (int(stats_forbidden or 0) == 403)
finally:
    goto_url(base + "/#/login"); wait_for_load(); time.sleep(0.4)
    stats_restore = rest_login()
    time.sleep(0.4)
    stats_admin_back = admin_session_live()
    stats_restore_ok = (stats_restore == 200) and bool(stats_admin_back)
stats_ok = stats_dash_ok and stats_403_ok and stats_restore_ok
results["stats_dashboard"] = (stats_ok, f"admin={stats_admin} window_refetch={win_switched} nonadmin_status={stats_forbidden} restore_ok={stats_restore_ok}")
shot("20c-stats-admin-restored")

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

# --- Check 22 (#35): persisted, non-destructive file rotation (v3.5.2) ----------
# Rotation is stored per file and applied on serve; it must SURVIVE a reload (proving it
# is server-persisted, not a transient display state). END-TO-END as admin: open the
# document-view content tab, wait out the async raster processing (the rotate endpoint
# returns a retriable "still processing" error until the raster exists), then rotate the
# file 90° — via the real UI "Rotate right" control if it can be targeted, else via a
# credentialed POST /api/file/:id/rotation (rotation=90) issued from inside the page
# (still a real-Chrome, real-session request). THEN the LOAD-BEARING assertion: RELOAD
# and read the AUTHORITATIVE GET /api/document/:id?files=true — the matching file's
# rotation must equal 90. A failed rotate or a non-persisted value FAILS this check.
rotation_via = "none"
rotation_reload = None
rotation_ok = False
if not rot_doc_id or not rot_file_id:
    results["rotation_persist"] = (False, "missing rotation seed (doc/file id)")
else:
    goto_url(base + f"/#/document/view/{rot_doc_id}/content"); time.sleep(2.5); wait_for_load()
    # Wait for the async raster processing to finish so the rotate endpoint does not return
    # "still processing" — GET /api/file/list?id=<doc> until every file's processing flag clears.
    proc_deadline = time.time() + 30
    processing_done = False
    while time.time() < proc_deadline:
        processing_done = js("""
          return (() => fetch(location.origin + '/api/file/list?id=arg_id', {credentials:'include'})
            .then(r => r.ok ? r.json() : {files: []})
            .then(d => { const fs = d.files||[]; return fs.length > 0 && fs.every(f => !f.processing); })
            .catch(() => false))();
        """.replace("arg_id", rot_doc_id))
        if processing_done:
            break
        time.sleep(1.0)
    shot("22a-rotation-before")
    # Prefer the real UI rotate control (a genuine real-Chrome interaction).
    ui_clicked = js("""
      return (() => {
        const card = document.querySelector('.file-preview-card');
        const scope = card || document;
        const btns = [...scope.querySelectorAll('button, [role=button]')];
        const rot = btns.find(b => {
          const label = ((b.getAttribute('aria-label')||'') + ' ' + (b.title||'') + ' ' + (b.textContent||''));
          return /rotate\\s*right/i.test(label);
        });
        if (rot) { rot.click(); return true; }
        return false;
      })();
    """)
    if ui_clicked:
        rotation_via = "ui"
        time.sleep(2.0)
    else:
        # Fall back to a credentialed POST from inside the page (still real browser + real session).
        rot_status = js("""
          return (() => fetch(location.origin + '/api/file/arg_id/rotation', {
            method: 'POST', credentials: 'include',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: 'rotation=90'
          }).then(r => r.status).catch(() => 0))();
        """.replace("arg_id", rot_file_id))
        rotation_via = f"post({rot_status})"
        time.sleep(1.5)
    # RELOAD (navigate away and back) so the assertion reads a freshly-hydrated state, then
    # the AUTHORITATIVE persistence read-back: the file's stored rotation must be 90.
    goto_url(base + "/#/document"); time.sleep(1.0); wait_for_load()
    goto_url(base + f"/#/document/view/{rot_doc_id}/content"); time.sleep(2.0); wait_for_load()
    # Poll briefly — a UI click's rotate request + regeneration is async.
    rr_deadline = time.time() + 10
    while time.time() < rr_deadline:
        rotation_reload = js("""
          return (() => fetch(location.origin + '/api/document/arg_id?files=true', {credentials:'include'})
            .then(r => r.ok ? r.json() : {files: []})
            .then(d => { const f = (d.files||[]).find(x => x.id === arg_fid); return f ? f.rotation : null; })
            .catch(() => null))();
        """.replace("arg_id", rot_doc_id).replace("arg_fid", json.dumps(rot_file_id)))
        if rotation_reload == 90:
            break
        time.sleep(0.8)
    rotation_ok = (rotation_reload == 90)
    results["rotation_persist"] = (rotation_ok, f"rotated_via={rotation_via} processing_done={processing_done} reload_rotation={rotation_reload}")
    shot("22b-rotation-after-reload")

# --- Checks 10-11 + 16-17: anonymous / login-form, CONTEXT-ISOLATED ------------
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

    # --- Check 16 (behavior B): a DISABLED user is denied native form login --
    # The seeded dis_user was disabled via the admin API. Driving the real login
    # form must be REJECTED (stays on /login, no session). If the backend stopped
    # enforcing isDisabled(), the user would enter the app and this would fail.
    def form_login(u, p, code=None):
        goto_url(base + "/#/login"); wait_for_load(); time.sleep(0.8)
        js("""
          (() => {
            const uu = document.querySelector('input[type=text], input[autocomplete=username], #login-user');
            const pp = document.querySelector('input[type=password]');
            const set = (el, v) => { el.value = v; el.dispatchEvent(new Event('input', {bubbles:true})); };
            if (uu) set(uu, arg_u); if (pp) set(pp, arg_p);
          })();
        """.replace("arg_u", json.dumps(u)).replace("arg_p", json.dumps(p)))
        time.sleep(0.3)
        js("""
          (() => {
            const b = [...document.querySelectorAll('button')].find(x => /sign\\s*in/i.test(x.textContent||''));
            (b ?? document.querySelector('form button[type=submit]')).click();
          })();
        """)
        time.sleep(2.0); wait_for_load()

    form_login(dis_user, dis_pass)
    time.sleep(1.0)
    dis_url = cur_url()
    dis_alert = js("""
      return (() => !!document.querySelector('.p-message-error, [role=alert]'))();
    """)
    # Authoritative: the session is genuinely anonymous, not established.
    dis_anon = js("""
      return (() => fetch(location.origin + '/api/user', {credentials:'include'})
        .then(r => r.ok ? r.json() : {})
        .then(u => u && u.anonymous === true).catch(() => false))();
    """)
    disabled_login_ok = ("/login" in dis_url) and bool(dis_alert) and bool(dis_anon)
    results["disabled_login_denied"] = (disabled_login_ok, f"url={dis_url} alert={dis_alert} anonymous={dis_anon}")
    shot("16-disabled-login-denied")

    # --- Check 17 (behavior A): TOTP challenge + full valid-code login -------
    # Submitting the TOTP user's password reveals the OTP field (400
    # ValidationCodeRequired). We then compute the CURRENT valid code and complete
    # the login — a genuine end-to-end 2FA login, code verified by the real server.
    goto_url(base + "/#/login"); wait_for_load(); time.sleep(0.8)
    js("""
      (() => {
        const uu = document.querySelector('input[type=text], input[autocomplete=username], #login-user');
        const pp = document.querySelector('input[type=password]');
        const set = (el, v) => { el.value = v; el.dispatchEvent(new Event('input', {bubbles:true})); };
        if (uu) set(uu, arg_u); if (pp) set(pp, arg_p);
      })();
    """.replace("arg_u", json.dumps(totp_user)).replace("arg_p", json.dumps(totp_pass)))
    time.sleep(0.3)
    js("""
      (() => {
        const b = [...document.querySelectorAll('button')].find(x => /sign\\s*in/i.test(x.textContent||''));
        (b ?? document.querySelector('form button[type=submit]')).click();
      })();
    """)
    time.sleep(2.0); wait_for_load()
    # The OTP field (#login-code) is revealed by the challenge and we are still on /login.
    otp_field = js("return (() => !!document.querySelector('#login-code'))();")
    otp_url = cur_url()
    shot("17a-totp-challenge")
    # Compute the valid code and resubmit.
    code = totp_now(totp_secret)
    js("""
      (() => {
        const c = document.querySelector('#login-code');
        if (c) { c.value = arg_code; c.dispatchEvent(new Event('input', {bubbles:true})); }
      })();
    """.replace("arg_code", json.dumps(code)))
    time.sleep(0.3)
    js("""
      (() => {
        const b = [...document.querySelectorAll('button')].find(x => /sign\\s*in/i.test(x.textContent||''));
        (b ?? document.querySelector('form button[type=submit]')).click();
      })();
    """)
    time.sleep(2.5); wait_for_load()
    totp_url = cur_url()
    # Authoritative: a session for the TOTP user is now live (not anonymous).
    totp_live = js("""
      return (() => fetch(location.origin + '/api/user', {credentials:'include'})
        .then(r => r.ok ? r.json() : {})
        .then(u => (u && u.username === arg_u && u.anonymous !== true)).catch(() => false))();
    """.replace("arg_u", json.dumps(totp_user)))
    totp_ok = bool(otp_field) and ("/login" in otp_url) and ("/document" in totp_url) and bool(totp_live)
    results["totp_login"] = (totp_ok, f"otp_field={otp_field} challenge_url={otp_url} final_url={totp_url} session_live={totp_live}")
    shot("17b-totp-logged-in")
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
    "tag_picker": "check13: doc-edit tag MultiSelect filter winnowed options + selected chip is colored (behavior C)",
    "tag_overflow": "check14: +N tag overflow popover revealed the hidden tags, not clipped (behavior D)",
    "dblclick_open": "check15: double-clicking a document row navigated to the full view (behavior D)",
    "share_anonymous": "check10: anonymous visitor saw the shared document (context-isolated)",
    "guard_redirect": "check11: anonymous /settings/users redirected to /login (context-isolated)",
    "disabled_login_denied": "check16: a disabled user was denied native form login (behavior B, context-isolated)",
    "totp_login": "check17: TOTP challenge revealed the OTP field + a valid code completed login (behavior A, context-isolated)",
    "favorites_roundtrip": "check18: favorites star (real UI click) round-trips via /api/favorite + /api/document, idempotent PUT + DELETE (#41)",
    "gallery_view": "check19: gallery view toggle rendered a card for the seeded doc + persisted the mode in localStorage across reload (#39)",
    "stats_dashboard": "check20: admin stats dashboard rendered totals/chart/storage + window refetch, and a non-admin got 403 (context-isolated) (#40)",
    "rich_description_sanitized": "check21: rich description stored inert (no script/onerror/javascript:) with legit formatting kept + Quill editor present (#38)",
    "rotation_persist": "check22: file rotation persists across reload — POST/UI rotate then GET document files[].rotation==90 (#35/v3.5.2)",
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

# --- Cleanup: trash the documents + delete the seeded users this run created ---
# Best-effort; every artifact is uniquely RUN-token stamped so a missed cleanup
# never collides with a later run. The top-of-script cookie_jar admin session is
# still valid (the harness restored admin at the end of its logged-out region).
for d in "${doc_id:-}" "${ovf_doc_id:-}" "${fav_doc_id:-}" "${rich_doc_id:-}" "${rot_doc_id:-}"; do
  [ -n "${d}" ] || continue
  curl -sf -b "${cookie_jar}" -X DELETE "${base_url}/api/document/${d}" >/dev/null 2>&1 \
    && echo "[cleanup] trashed document ${d}" | tee -a "${art_dir}/checks.log" \
    || echo "[cleanup] could not trash document ${d} (non-fatal)" | tee -a "${art_dir}/checks.log"
done
for u in "${dis_user:-}" "${totp_user:-}" "${stats_user:-}"; do
  [ -n "${u}" ] || continue
  curl -sf -b "${cookie_jar}" -X DELETE "${base_url}/api/user/${u}" >/dev/null 2>&1 \
    && echo "[cleanup] deleted user ${u}" | tee -a "${art_dir}/checks.log" \
    || echo "[cleanup] could not delete user ${u} (non-fatal)" | tee -a "${art_dir}/checks.log"
done

if [ "${fail}" -ne 0 ]; then
  echo "== RESULT: FAIL (see ${art_dir}) =="
  exit 1
fi
echo "== RESULT: all browser-harness checks passed (artifacts: ${art_dir}) =="
