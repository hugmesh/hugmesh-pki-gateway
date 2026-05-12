# snapdragon-id-gateway — Gherkin / BDD e2e suite

End-to-end coverage for the id-gateway's two complementary security paths:

| Surface | Path | Tooling |
|---|---|---|
| OAuth2 Authorization-Code login (browser flow) | `oauth2Login` | [`features/auth/oauth-login-flow.feature`](features/auth/oauth-login-flow.feature) |
| Bearer-JWT validation (programmatic) | `oauth2ResourceServer(jwt)` | [`features/jwt/resource-server.feature`](features/jwt/resource-server.feature) |
| Per-role access matrix | both | [`features/auth/per-role-access.feature`](features/auth/per-role-access.feature) |

Mirrors the same playwright-bdd shape used by [`snapdragon.games`](../../snapdragon.games/e2e) and
[`snapdragon-actions`](../../snapdragon-actions/e2e) so a reviewer reading across
repos doesn't need to context-switch.

## Run

```bash
# 1. Bring up the full stack (Casdoor + Postgres + gateway) and mint
#    one JWT per role into e2e/.tokens.json.
bash e2e/scripts/bring-up-stack.sh

# 2. Install deps + generate the BDD-derived spec files + run them.
npm --prefix e2e install
npm --prefix e2e test

# 3. (Optional) Open the rendered HTML report with screenshots + videos.
npm --prefix e2e run report

# 4. Tear down.
bash e2e/scripts/bring-up-stack.sh stop
```

## What each feature pins

### `auth/oauth-login-flow.feature` — gateway as an OAuth2 _client_

A logged-out browser hitting a gated route must:

- Get bounced (`302`) to Casdoor's `/login/oauth/authorize` endpoint.
- Carry the gateway's own `client_id` query param.
- Carry a `redirect_uri` pointing back at `/login/oauth2/code/casdoor`.

Plus the negative case: `/actuator/health` is `permitAll`, so anonymous
requests get `200` and **no** Location header.

### `jwt/resource-server.feature` — gateway as a Bearer-token validator

A programmatic client carrying `Authorization: Bearer <jwt>` is
validated against Casdoor's JWKS:

- No header on a gated route → `401` or `302` (either of the two
  authentication-entry-point chains is acceptable; deeper coverage in
  the per-role matrix).
- Malformed Bearer → `401`.
- Expired Casdoor JWT → `401`.
- Valid `GAME-MASTER` JWT → `200`.

### `auth/per-role-access.feature` — per-role matrix

The security gate enforced by `SecurityConfig`:

```
/actuator/health/**, /actuator/info  →  permitAll
/actuator/**                         →  hasRole("GAME-MASTER")
anyExchange()                        →  authenticated
```

Pinned per role: `USER`, `PRODUCER`, `MOD`, `GAME-MASTER`, `ADMIN`.
Game-master is the **only** role that reaches `/actuator/gateway/routes`;
the admin role authenticates but the infra surface stays sealed.

This is the test that makes the platform's security boundary
machine-checkable: "no health or infrastructure URLs are
accessible to anyone who does not have a game-master role with
a token authorised by snapdragon.systems."

## Token loading

The bring-up script writes one JWT per role to `e2e/.tokens.json`. The
auth steps read from that file; if it's missing, they fall back to
`TOKEN_<ROLE_UPPER_UNDERSCORE>` env vars (e.g. `TOKEN_GAME_MASTER`,
`TOKEN_ADMIN`). The `__expired` slot is populated separately via
`TOKEN_EXPIRED` for the resource-server expiry test.

## Reporting

Per-step PNG screenshots land in the Playwright HTML report via the
`AfterStep` hook in [`features/support/fixtures.ts`](features/support/fixtures.ts).
Videos are retained for every scenario (`video: 'on'` in
`playwright.config.ts`) so a reviewer can replay the OAuth redirect
chain end-to-end without re-running the stack.
