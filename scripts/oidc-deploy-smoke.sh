#!/usr/bin/env bash
# OIDC deploy-time smoke check for a PRODUCTION Teedy deployment.
#
# WHY THIS IS NOT A PLAYWRIGHT TEST
# ---------------------------------
# The CI e2e container (scripts/e2e-run.sh) boots Teedy with NO OIDC/Authelia
# system properties, so `oidc_enabled` is false there and there is nothing an
# in-browser test could drive. OIDC is only configured on the real deployment
# (Saturn, behind Authelia). This script is therefore a DEPLOY-TIME check that reads
# the OIDC config back from the RUNNING container against an EXPLICITLY
# OIDC-configured target — the house rule for auth surfaces the agent cannot itself
# log into (docker-exec + authoritative read-back, not a login screenshot).
#
# WHAT IT VERIFIES (authoritative runtime reads, no source/compose inspection)
#   1. GET /api/app  -> "oidc_enabled": true
#        This is the exact field the SPA reads to render the "Login with SSO" button,
#        so a true value proves docs.oidc_enabled is live in the JVM.
#   2. GET /api/oidc/login?returnUrl=/#/document  -> HTTP 307 redirect whose Location
#      is the IdP AUTHORIZATION endpoint carrying response_type=code, client_id and
#      redirect_uri. This proves issuer / client_id / authorization_endpoint /
#      redirect_uri are all correctly wired. A MISCONFIGURED OIDC setup instead
#      redirects to /#/login?error=oidc (OidcResource.java), which this script treats
#      as a FAILURE.
#
# SECRET HYGIENE
#   The OIDC client secret (docs.oidc_client_secret) is used ONLY server-side in the
#   /callback token exchange; it NEVER appears in /api/app or in the /login redirect.
#   This script never reads JAVA_TOOL_OPTIONS, never dumps the JVM properties, and
#   redacts client_id in its output. It cannot leak the secret because it never touches
#   it.
#
# USAGE
#   scripts/oidc-deploy-smoke.sh                 # defaults to the Saturn teedy container
#   TEEDY_SSH=saturn.local TEEDY_CONTAINER=teedy scripts/oidc-deploy-smoke.sh
#   TEEDY_LOCAL=1 scripts/oidc-deploy-smoke.sh   # run docker locally (no SSH), same host
#
# ENV
#   TEEDY_SSH        SSH host of the Docker host (default: saturn.local). Ignored if
#                    TEEDY_LOCAL=1.
#   TEEDY_CONTAINER  container name (default: teedy)
#   TEEDY_LOCAL      if set to 1, run `docker exec` locally instead of over SSH
#   DOCKER_BIN       docker path on the host (default: /usr/local/bin/docker for
#                    Synology; set to `docker` for a standard host)
set -euo pipefail

ssh_host="${TEEDY_SSH:-saturn.local}"
container="${TEEDY_CONTAINER:-teedy}"
docker_bin="${DOCKER_BIN:-/usr/local/bin/docker}"

# Run a command inside the Teedy container, either locally or over SSH. Synology
# needs sudo for docker; a local standard host typically does not.
exec_in_container() {
  local inner="$1"
  if [ "${TEEDY_LOCAL:-}" = "1" ]; then
    ${docker_bin} exec "${container}" sh -c "${inner}"
  else
    # shellcheck disable=SC2029  # we intentionally expand locally into the remote cmd
    ssh "${ssh_host}" "sudo ${docker_bin} exec ${container} sh -c '${inner}'"
  fi
}

fail() { echo "FAIL: $*" >&2; exit 1; }

echo "== Teedy OIDC deploy smoke =="
echo "target: ${TEEDY_LOCAL:+local }${TEEDY_LOCAL:-${ssh_host}} container=${container}"
echo

# --- 1. /api/app advertises oidc_enabled=true ---
echo "[1/2] GET /api/app -> oidc_enabled"
app_json="$(exec_in_container 'curl -fsS http://localhost:8080/api/app')" \
  || fail "could not reach /api/app in the container"

# Extract the boolean without a JSON parser dependency (grep the exact token).
if echo "${app_json}" | grep -q '"oidc_enabled"[[:space:]]*:[[:space:]]*true'; then
  echo "  ok: oidc_enabled=true"
else
  echo "  app response: ${app_json}" >&2
  fail "oidc_enabled is not true on this deployment — OIDC is NOT live"
fi

# --- 2. /api/oidc/login redirects to the IdP authorization endpoint ---
echo "[2/2] GET /api/oidc/login -> IdP authorization redirect"
# -sS silent-but-errors, -o /dev/null discard body, -D - dump headers to stdout, no -L
# so we capture the redirect itself. Report the status + Location (redacted).
headers="$(exec_in_container 'curl -sS -o /dev/null -D - "http://localhost:8080/api/oidc/login?returnUrl=/%23/document"')" \
  || fail "could not reach /api/oidc/login in the container"

status_line="$(printf '%s\n' "${headers}" | head -n1 | tr -d '\r')"
status_code="$(printf '%s\n' "${status_line}" | awk '{print $2}')"
location="$(printf '%s\n' "${headers}" | grep -i '^location:' | head -n1 | sed 's/^[Ll]ocation:[[:space:]]*//' | tr -d '\r')"

# The initiation MUST be an HTTP 3xx redirect. Anything else (200 shell, 4xx, 5xx)
# means SSO was not initiated — validate the status, don't just report it.
case "${status_code}" in
  3[0-9][0-9]) : ;;  # 3xx: a redirect, as expected
  *) echo "  status: ${status_line}" >&2
     fail "expected a 3xx redirect from /api/oidc/login, got '${status_code:-none}'" ;;
esac

if [ -z "${location}" ]; then
  echo "  status: ${status_line}" >&2
  fail "no redirect Location — /api/oidc/login did not initiate SSO"
fi

# A misconfigured deployment redirects to the SPA error page instead of the IdP.
case "${location}" in
  */#/login?error=oidc|*%23/login?error=oidc)
    fail "OIDC is enabled but MISCONFIGURED (redirect to /#/login?error=oidc)" ;;
esac

# The redirect MUST be an OIDC authorization request (proves endpoints + client_id).
echo "${location}" | grep -q 'response_type=code' \
  || fail "redirect is not an OIDC authorization request: ${location%%\?*}?<redacted>"
echo "${location}" | grep -q 'client_id=' \
  || fail "redirect carries no client_id — client_id not configured"
echo "${location}" | grep -q 'redirect_uri=' \
  || fail "redirect carries no redirect_uri — redirect_uri not configured"

# Redact everything sensitive: show only the authorization endpoint (scheme+host+path),
# never the full query (which carries client_id / state / PKCE challenge).
auth_endpoint="${location%%\?*}"
echo "  ok: ${status_code} -> ${auth_endpoint}?<redacted: response_type,client_id,redirect_uri,scope,state,nonce,PKCE>"

echo
echo "PASS: OIDC login initiation is wired on this deployment."
echo "      Verified: oidc_enabled=true, and /api/oidc/login issues a ${status_code}"
echo "      redirect to the IdP authorization endpoint with client_id + redirect_uri."
echo
echo "      SCOPE: this is an INITIATION-only check. It does NOT exercise the token"
echo "      exchange, so it cannot detect a wrong-but-nonempty client secret (that is"
echo "      only used server-side at /callback). A real end-to-end SSO login through"
echo "      the IdP is still required to prove the secret itself is correct."
echo "      (Client secret never read/printed by this script.)"
