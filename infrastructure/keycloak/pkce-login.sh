#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# OAuth 2.0 Authorization Code Flow + PKCE, step by step, with curl.
#
# This is exactly what the Angular app and the browser will do in Phase 7.
# Here every HTTP exchange is visible, so you can see the protocol itself.
#
#   ./infrastructure/keycloak/pkce-login.sh alice           # explained walkthrough
#   TOKEN=$(./infrastructure/keycloak/pkce-login.sh alice)  # explanations go to stderr,
#                                                            # the access token to stdout
#
# The full token response is saved to ${TMPDIR:-/tmp}/banking-<user>-tokens.json
# (needed for the refresh and logout examples in the docs).
#
# Requires: bash, curl, openssl, python3. Keycloak must be running (docker compose up -d).
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/../../.env"
if [[ -z "${DEMO_USER_PASSWORD:-}" && -f "$ENV_FILE" ]]; then
  DEMO_USER_PASSWORD="$(grep -E '^DEMO_USER_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)"
fi

USERNAME="${1:?usage: $0 <username> [password]   (users: alice, bob, sam, ada)}"
PASSWORD="${2:-${DEMO_USER_PASSWORD:?set DEMO_USER_PASSWORD or pass the password as 2nd argument}}"

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
REALM="banking"
CLIENT_ID="banking-frontend"
REDIRECT_URI="http://localhost:4200/"
OIDC="$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect"
TOKEN_FILE="${TMPDIR:-/tmp}/banking-${USERNAME}-tokens.json"

COOKIES="$(mktemp)"
trap 'rm -f "$COOKIES"' EXIT

say()  { printf '\n\033[1;34m%s\033[0m\n' "$*" >&2; }
info() { printf '    %s\n' "$*" >&2; }
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
urlencode() { python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"; }

# --- Step 1: PKCE values, created by the client and never sent to anyone yet ----------------
say "Step 1  Client creates PKCE code_verifier (secret) and code_challenge = BASE64URL(SHA256(verifier))"
CODE_VERIFIER="$(openssl rand 48 | b64url)"                       # 64 chars, high entropy
CODE_CHALLENGE="$(printf '%s' "$CODE_VERIFIER" | openssl dgst -sha256 -binary | b64url)"
STATE="$(openssl rand 16 | b64url)"                                # CSRF protection for the redirect
NONCE="$(openssl rand 16 | b64url)"                                # binds the ID token to this login
info "code_verifier  = $CODE_VERIFIER   (kept in the client)"
info "code_challenge = $CODE_CHALLENGE  (sent to Keycloak)"
info "state = $STATE   nonce = $NONCE"

# --- Step 2: Redirect the browser to Keycloak's authorization endpoint -----------------------
AUTH_URL="$OIDC/auth?response_type=code&client_id=$CLIENT_ID&scope=openid%20profile%20email"
AUTH_URL+="&redirect_uri=$(urlencode "$REDIRECT_URI")&state=$STATE&nonce=$NONCE"
AUTH_URL+="&code_challenge=$CODE_CHALLENGE&code_challenge_method=S256"
say "Step 2  Browser → GET /auth (authorization request). Keycloak replies with its login page"
info "GET $AUTH_URL"
LOGIN_PAGE="$(curl -sS -c "$COOKIES" -b "$COOKIES" "$AUTH_URL")"
FORM_ACTION="$(printf '%s' "$LOGIN_PAGE" | python3 -c '
import html, re, sys
page = sys.stdin.read()
form = re.search(r"<form[^>]*id=\"kc-form-login\"[^>]*>", page, re.S)
action = re.search(r"action=\"([^\"]+)\"", form.group(0)) if form else None
print(html.unescape(action.group(1)) if action else "")
')"
if [[ -z "$FORM_ACTION" ]]; then
  echo "ERROR: no login form returned. Is Keycloak running and the realm imported?" >&2
  printf '%s\n' "$LOGIN_PAGE" | head -20 >&2
  exit 1
fi
info "login form posts to: ${FORM_ACTION%%\?*}?session_code=..."

# --- Step 3: User submits credentials TO KEYCLOAK (the app never sees the password) ----------
say "Step 3  User → POST credentials to Keycloak. On success Keycloak redirects to redirect_uri with ?code=..."
LOCATION="$(curl -sS -o /dev/null -w '%{redirect_url}' -c "$COOKIES" -b "$COOKIES" \
  --data-urlencode "username=$USERNAME" --data-urlencode "password=$PASSWORD" \
  --data-urlencode "credentialId=" "$FORM_ACTION")"
if [[ "$LOCATION" != "$REDIRECT_URI"* ]]; then
  echo "ERROR: login failed (wrong username/password, or account temporarily locked by brute-force protection)." >&2
  exit 1
fi
info "302 Location: ${LOCATION:0:110}..."
CODE="$(python3 -c 'import sys,urllib.parse as u; q=u.parse_qs(u.urlparse(sys.argv[1]).query); print(q["code"][0])' "$LOCATION")"
RETURNED_STATE="$(python3 -c 'import sys,urllib.parse as u; q=u.parse_qs(u.urlparse(sys.argv[1]).query); print(q["state"][0])' "$LOCATION")"

# --- Step 4: Client checks state (rejects forged redirects) ----------------------------------
say "Step 4  Client verifies the returned state matches what it sent"
[[ "$RETURNED_STATE" == "$STATE" ]] || { echo "ERROR: state mismatch, possible CSRF" >&2; exit 1; }
info "state OK. Authorization code (single use, valid ~60s): ${CODE:0:20}..."

# --- Step 5: Exchange code + verifier for tokens (back-channel POST) -------------------------
say "Step 5  Client → POST /token with code + code_verifier. Keycloak checks SHA256(verifier) == challenge"
info "POST $OIDC/token  grant_type=authorization_code client_id=$CLIENT_ID code=... code_verifier=..."
curl -sS -X POST "$OIDC/token" \
  --data-urlencode "grant_type=authorization_code" \
  --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "code=$CODE" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" \
  --data-urlencode "code_verifier=$CODE_VERIFIER" > "$TOKEN_FILE"

python3 - "$TOKEN_FILE" "$NONCE" >&2 <<'PY'
import base64, json, sys
tokens = json.load(open(sys.argv[1]))
if "access_token" not in tokens:
    print("ERROR:", tokens); sys.exit(1)

def decode(jwt):
    header, payload, _ = jwt.split(".")
    pad = lambda s: s + "=" * (-len(s) % 4)
    return (json.loads(base64.urlsafe_b64decode(pad(header))),
            json.loads(base64.urlsafe_b64decode(pad(payload))))

print("    token_type=%s  expires_in=%ss  refresh_expires_in=%ss  scope=%s" % (
    tokens["token_type"], tokens["expires_in"], tokens["refresh_expires_in"], tokens["scope"]))

_, id_claims = decode(tokens["id_token"])
assert id_claims["nonce"] == sys.argv[2], "nonce mismatch"
print("\n\033[1;34mStep 6  Client validates the ID token (nonce matches) and reads who logged in\033[0m")
print("    ID token (for the CLIENT, proves authentication):")
for k in ("iss", "aud", "sub", "preferred_username", "name", "email", "nonce"):
    print("      %-20s %s" % (k, id_claims.get(k)))

header, access = decode(tokens["access_token"])
print("\n    Access token (for the API, carries authorization):")
print("      header  alg=%s kid=%s" % (header["alg"], header["kid"]))
for k in ("iss", "aud", "sub", "azp", "preferred_username", "scope"):
    print("      %-20s %s" % (k, access.get(k)))
print("      %-20s %s" % ("realm_access.roles", access.get("realm_access", {}).get("roles")))
print("      %-20s %s" % ("exp - iat", "%ss" % (access["exp"] - access["iat"])))
PY

say "Done. Full token response saved to $TOKEN_FILE. Access token printed to stdout."
python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["access_token"])' "$TOKEN_FILE"
