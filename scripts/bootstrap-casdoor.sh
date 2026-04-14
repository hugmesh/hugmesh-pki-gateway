#!/bin/sh
# Registers the snapdragon-id-gateway OAuth2 client in a running Casdoor
# instance. Idempotent — safe to re-run. Intended to be launched as a
# one-shot docker-compose service once the `casdoor` service is healthy.
#
# Reads the following env vars (with defaults):
#   CASDOOR_URL                http://casdoor:8000
#   CASDOOR_ADMIN_ORG          built-in
#   CASDOOR_ADMIN_APP          app-built-in
#   CASDOOR_ADMIN_USERNAME     admin
#   CASDOOR_ADMIN_PASSWORD     123
#   GATEWAY_APP_NAME           snapdragon-id-gateway
#   GATEWAY_APP_DISPLAY_NAME   Snapdragon ID Gateway
#   CASDOOR_CLIENT_ID          (required — matches the gateway's env var)
#   CASDOOR_CLIENT_SECRET      (required — matches the gateway's env var)
#   GATEWAY_REDIRECT_URI       http://localhost:8080/login/oauth2/code/casdoor

set -eu

CASDOOR_URL="${CASDOOR_URL:-http://casdoor:8000}"
CASDOOR_ADMIN_ORG="${CASDOOR_ADMIN_ORG:-built-in}"
CASDOOR_ADMIN_APP="${CASDOOR_ADMIN_APP:-app-built-in}"
CASDOOR_ADMIN_USERNAME="${CASDOOR_ADMIN_USERNAME:-admin}"
CASDOOR_ADMIN_PASSWORD="${CASDOOR_ADMIN_PASSWORD:-123}"
GATEWAY_APP_NAME="${GATEWAY_APP_NAME:-snapdragon-id-gateway}"
GATEWAY_APP_DISPLAY_NAME="${GATEWAY_APP_DISPLAY_NAME:-Snapdragon ID Gateway}"
GATEWAY_REDIRECT_URI="${GATEWAY_REDIRECT_URI:-http://localhost:8080/login/oauth2/code/casdoor}"

: "${CASDOOR_CLIENT_ID:?CASDOOR_CLIENT_ID is required}"
: "${CASDOOR_CLIENT_SECRET:?CASDOOR_CLIENT_SECRET is required}"

COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

log() { printf '[bootstrap-casdoor] %s\n' "$*"; }

# ---------------------------------------------------------------------------
# 1. Wait for Casdoor OIDC discovery to become reachable.
# ---------------------------------------------------------------------------
log "Waiting for Casdoor at $CASDOOR_URL ..."
attempts=0
until curl -fsS "$CASDOOR_URL/.well-known/openid-configuration" >/dev/null 2>&1; do
  attempts=$((attempts + 1))
  if [ "$attempts" -ge 60 ]; then
    log "Casdoor not reachable after 60 attempts; aborting."
    exit 1
  fi
  sleep 2
done
log "Casdoor is up."

# ---------------------------------------------------------------------------
# 2. Idempotency check — skip if the gateway app already exists.
#    Casdoor returns a JSON wrapper like {"status":"ok","data":{...}} on
#    success and {"status":"error","msg":"..."} on failure. We can check the
#    top-level "status" field without needing jq.
# ---------------------------------------------------------------------------
existing=$(curl -fsS \
  "$CASDOOR_URL/api/get-application?id=${CASDOOR_ADMIN_ORG}/${GATEWAY_APP_NAME}" \
  2>/dev/null || printf '{}')

if printf '%s' "$existing" | grep -qE '"status"[[:space:]]*:[[:space:]]*"ok"' \
   && printf '%s' "$existing" | grep -qE "\"name\"[[:space:]]*:[[:space:]]*\"$GATEWAY_APP_NAME\""; then
  log "Application '$GATEWAY_APP_NAME' already exists. Nothing to do."
  exit 0
fi

# ---------------------------------------------------------------------------
# 3. Log in as the built-in admin so subsequent admin API calls are authorized
#    via the session cookie.
# ---------------------------------------------------------------------------
log "Logging in as $CASDOOR_ADMIN_ORG/$CASDOOR_ADMIN_USERNAME ..."
login_body=$(cat <<JSON
{
  "organization": "$CASDOOR_ADMIN_ORG",
  "application": "$CASDOOR_ADMIN_APP",
  "username": "$CASDOOR_ADMIN_USERNAME",
  "password": "$CASDOOR_ADMIN_PASSWORD",
  "type": "login"
}
JSON
)

login_resp=$(curl -fsS -c "$COOKIE_JAR" \
  -H 'Content-Type: application/json' \
  -X POST "$CASDOOR_URL/api/login" \
  -d "$login_body")

if ! printf '%s' "$login_resp" | grep -qE '"status"[[:space:]]*:[[:space:]]*"ok"'; then
  log "Admin login failed. Response: $login_resp"
  exit 1
fi
log "Logged in."

# ---------------------------------------------------------------------------
# 4. Create the application. Shape documented at
#    https://door.casdoor.com/swagger/swagger.json#/definitions/object.Application
# ---------------------------------------------------------------------------
log "Creating application '$GATEWAY_APP_NAME' ..."
app_body=$(cat <<JSON
{
  "owner": "$CASDOOR_ADMIN_ORG",
  "name": "$GATEWAY_APP_NAME",
  "organization": "$CASDOOR_ADMIN_ORG",
  "displayName": "$GATEWAY_APP_DISPLAY_NAME",
  "description": "Snapdragon ID Gateway — reactive Spring Cloud Gateway OAuth2 client",
  "clientId": "$CASDOOR_CLIENT_ID",
  "clientSecret": "$CASDOOR_CLIENT_SECRET",
  "redirectUris": ["$GATEWAY_REDIRECT_URI"],
  "grantTypes": ["authorization_code", "refresh_token"],
  "tokenFormat": "JWT",
  "expireInHours": 168,
  "refreshExpireInHours": 168,
  "enablePassword": true,
  "enableSignUp": true,
  "enableSigninSession": false,
  "enableAutoSignin": false,
  "enableCodeSignin": false,
  "signupItems": [
    {"name": "Username", "visible": true, "required": true, "rule": "None"},
    {"name": "Password", "visible": true, "required": true, "rule": "None"}
  ]
}
JSON
)

create_resp=$(curl -fsS -b "$COOKIE_JAR" \
  -H 'Content-Type: application/json' \
  -X POST "$CASDOOR_URL/api/add-application" \
  -d "$app_body")

if ! printf '%s' "$create_resp" | grep -qE '"status"[[:space:]]*:[[:space:]]*"ok"'; then
  log "add-application failed. Response: $create_resp"
  exit 1
fi

log "Application '$GATEWAY_APP_NAME' registered in Casdoor."
log "  clientId=$CASDOOR_CLIENT_ID"
log "  redirectUri=$GATEWAY_REDIRECT_URI"
