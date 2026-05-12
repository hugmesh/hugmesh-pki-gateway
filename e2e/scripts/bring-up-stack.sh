#!/usr/bin/env bash
# Bring up the id-gateway e2e environment:
#   • postgres primary + replica (gateway routes table)
#   • casdoor (OIDC issuer)
#   • casdoor-bootstrap (one-shot — registers gateway as OAuth2 client)
#   • liquibase (gateway schema)
#   • gateway on :8080
#
# Then mints per-role bearer tokens via Casdoor's /api/login endpoint
# and writes them to e2e/.tokens.json so the auth steps can read them
# without an admin-API round-trip per scenario.
#
# Per-role seed users live in the Casdoor app the bootstrap script
# registered; their passwords default to `e2e-pass`. Override any of
# them individually via E2E_<ROLE>_PASSWORD or globally via E2E_PASSWORD.
#
# Usage:
#   bash e2e/scripts/bring-up-stack.sh         # bring up + wait healthy + mint
#   bash e2e/scripts/bring-up-stack.sh stop    # tear down + drop .tokens.json
#
# Idempotent — safe to re-run while iterating on a scenario.

set -euo pipefail

cd "$(dirname "$0")/../.."  # repo root

CASDOOR_URL="${CASDOOR_URL:-http://localhost:8000}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
CASDOOR_ORG="${CASDOOR_ORG:-snapdragon-systems}"
CASDOOR_APP="${CASDOOR_APP:-snapdragon-id-gateway}"
E2E_PASSWORD="${E2E_PASSWORD:-e2e-pass}"

case "${1:-up}" in
  stop|down)
    docker compose down -v
    rm -f e2e/.tokens.json
    exit 0
    ;;
esac

echo "→ docker compose up..."
docker compose up -d --wait

echo "→ Waiting for Casdoor at $CASDOOR_URL ..."
for i in $(seq 1 60); do
  curl -sf "$CASDOOR_URL/.well-known/openid-configuration" >/dev/null && break
  sleep 2
  if [ "$i" -eq 60 ]; then
    echo "Casdoor not healthy after 120s" >&2
    docker compose logs casdoor | tail -50
    exit 1
  fi
done

echo "→ Waiting for gateway at $GATEWAY_URL ..."
for i in $(seq 1 60); do
  curl -sf "$GATEWAY_URL/actuator/health" >/dev/null && break
  sleep 2
  if [ "$i" -eq 60 ]; then
    echo "Gateway not healthy after 120s" >&2
    docker compose logs gateway | tail -50
    exit 1
  fi
done

# ── Token minting ──────────────────────────────────────────────────
# Casdoor's /api/login-with-password mints a JWT for the named user
# against the gateway's registered application. The JWT validates
# against the gateway's resource-server config because both sides
# share the issuer URI + JWKS.
#
# Each role's seed user is named lowercased after the role: user,
# producer, mod, game-master, admin. Per the security gate, the
# game-master role is the ONLY one that should reach actuator/**;
# the admin role authenticates but does not unlock infra surfaces.
#
# Roles must already be assigned to the seed users in Casdoor —
# done by a follow-up bootstrap step the gateway operator runs once.
# When that step is missing, this script still writes a tokens file
# with whatever tokens it could mint; missing roles surface as
# clear errors at scenario time.

mint_token() {
  local role="$1"
  local username
  username="$(echo "$role" | tr 'A-Z' 'a-z')"
  local password
  password="${E2E_PASSWORD}"
  # Per-role override knob (E2E_USER_PASSWORD, E2E_GAME_MASTER_PASSWORD, …)
  local override_var="E2E_$(echo "$role" | tr 'a-z' 'A-Z' | tr '-' '_')_PASSWORD"
  password="${!override_var:-$password}"

  local resp
  resp=$(curl -sf -X POST "$CASDOOR_URL/api/login" \
    -H "Content-Type: application/json" \
    -d "{
      \"organization\": \"$CASDOOR_ORG\",
      \"application\":  \"$CASDOOR_APP\",
      \"username\":     \"$username\",
      \"password\":     \"$password\",
      \"type\":         \"login\"
    }" 2>/dev/null) || return 1

  # Casdoor's /api/login returns the JWT in `data` when status=ok.
  local token
  token=$(echo "$resp" | grep -oE '"data"[[:space:]]*:[[:space:]]*"[^"]+"' \
                       | sed -E 's/.*"data"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/' \
                       || true)
  if [ -z "$token" ]; then
    echo "  ⚠ no token for $role (user=$username)" >&2
    return 1
  fi
  echo "$token"
}

echo "→ Minting per-role bearer tokens..."
TOKENS_JSON='{'
for role in USER PRODUCER MOD GAME-MASTER ADMIN; do
  if token=$(mint_token "$role"); then
    TOKENS_JSON+="\"$role\":\"$token\","
    echo "  ✓ $role"
  fi
done

# Build an expired JWT for the resource-server's expiry test. The
# easy path: take any minted token and rewrite its `exp` claim into
# the past + re-sign. Because we don't have the issuer's private key
# here, the simpler approach is to take the freshly-minted GAME-MASTER
# token (still validates against JWKS) and have Casdoor mint it with
# a 1-second TTL via the OAuth2 token endpoint instead. For e2e
# correctness, what matters is that the gateway sees a JWT whose
# `exp` is in the past — Casdoor's regular `expireInHours` is too
# generous, so we leave the `__expired` slot for the test driver to
# fill via the TOKEN_EXPIRED env knob.
if [ -n "${TOKEN_EXPIRED:-}" ]; then
  TOKENS_JSON+="\"__expired\":\"$TOKEN_EXPIRED\","
fi
# Strip trailing comma + close.
TOKENS_JSON="${TOKENS_JSON%,}}"
echo "$TOKENS_JSON" > e2e/.tokens.json

echo "✓ Stack up. Tokens in e2e/.tokens.json."
echo "  Run: npm --prefix e2e test"
