# hugmesh-pki-gateway

Per-node-type instance of the HugMesh Spring Cloud Gateway — the **identity
tier** front door. It fronts Casdoor (auth / login / OIDC) and headscale (the
VPN/tailnet control plane). Internal-only (ingress class `nginx-internal`),
never internet-facing, and it validates the internal Casdoor JWT — no external
login is exposed.

It is **the same image and code as
[hugmesh-gateway](https://github.com/hugmesh/hugmesh-gateway)**.
Like its [be-](https://github.com/hugmesh/hugmesh-be-gateway) and
[fe-gateway](https://github.com/hugmesh/hugmesh-fe-gateway)
siblings this repo is a **thin overlay**: only the `SPRING_APPLICATION_NAME`, the
Postgres-backed route set (Liquibase), and the downstream URIs differ. The image
builds `FROM` the shared gateway image so every device runs identical, audited
gateway code — the same security filter chain, RBAC, domain-scoping converter,
and Cassandra-cached revocation / route-access / URL registry.

```bash
# bases off the sibling hugmesh-gateway image (GATEWAY_IMAGE build-arg)
docker build -t hugmesh-systems/pki-gateway:dev .
```

## Tier & namespace

| | |
|---|---|
| **Tier** | identity / PKI |
| **Namespace** | `hugmesh-pki` (downstreams resolve at `*.hugmesh-pki.svc.cluster.local`) |
| **`SPRING_APPLICATION_NAME`** | `hugmesh-pki-gateway` (set in the `Dockerfile`; selects this instance's route-access rows) |
| **Listen port** | `8080` (the gateway itself, `EXPOSE`d in the `Dockerfile`); downstreams are Casdoor `:8000` and headscale `:8080` |
| **Ingress** | `nginx-internal` — cluster-internal only, never internet-facing |
| **Sibling gateways** | `hugmesh-be-gateway` (API + matrix + mastodon), `hugmesh-fe-gateway` (host + remotes) |

## What it routes

The route table is config-driven (`application-pki.yml`, mirrored into the
`gateway_routes` Liquibase seed). All four routes **this repo configures** are
identity-tier downstreams in the `hugmesh-pki` namespace (the actual deployed
table has two more — see the callout below):

```mermaid
flowchart LR
    ingress["nginx-internal ingress<br/>(cluster-internal only)"] --> gw["hugmesh-pki-gateway<br/>(shared gateway image +<br/>this repo's route config/seed)"]
    gw -->|Path=/api/auth/**| casdoor["Casdoor :8000<br/>(OIDC IdP — auth, login, JWKS)"]
    gw -->|Path=/login/**| casdoor
    gw -->|Path=/.well-known/**| casdoor
    gw -->|Host=scale.hugmesh.net| headscale["headscale :8080<br/>(tailnet / VPN control plane)"]
    beGw["hugmesh-be-gateway<br/>(API + matrix + mastodon tier)"] -.->|validates JWTs minted by| casdoor
    feGw["hugmesh-fe-gateway<br/>(host + remotes tier)"] -.->|validates JWTs minted by| casdoor
```

*Solid arrows are this gateway's own proxied routes; dashed arrows show why
the sibling gateways depend on the Casdoor this gateway fronts (they validate
JWTs it mints, per [How it relates](#how-it-relates-to-the-gateways--casdoor--federation--multi-tenancy)
below) — they do not send traffic through this gateway itself.*

| Route id | Predicate | Downstream | Purpose |
|----------|-----------|------------|---------|
| `casdoor-auth` | `Path=/api/auth/**` | Casdoor `:8000` | OAuth2 / token endpoints |
| `casdoor-login` | `Path=/login/**` | Casdoor `:8000` | hosted login UI |
| `casdoor-oidc` | `Path=/.well-known/**` | Casdoor `:8000` | OIDC discovery / JWKS |
| `headscale` | `Host=scale.hugmesh.net` | headscale `:8080` | tailnet / VPN control plane |

`CASDOOR_URI`, `CASDOOR_ISSUER_URI`, and `HEADSCALE_URI` are env-overridable
(defaults point at the in-namespace services). `CASDOOR_ISSUER_URI` is the
issuer the OAuth2 resource server validates JWTs against.

> **The deployed route table has two routes this local-dev mirror does not
> (KNOWN GAP).** The generator's ArgoCD Application
> ([`specs/cluster/apps/20-gateway-pki.yaml`](https://github.com/hugmesh/hugmesh-generator/blob/main/specs/cluster/apps/20-gateway-pki.yaml))
> inserts two additional routes *ahead of* `casdoor-auth`/`casdoor-login`:
> `signup-gate-signup` (`Path=/api/signup`) and `signup-gate-login`
> (`Path=/api/login`), both proxied to the `signup-gate` service instead of
> straight to Casdoor. `signup-gate` sits in front of the paths that *mint* a
> username (native self-signup and OAuth-provider signup-completion) and calls
> the federation availability endpoint to reject a confusable/look-alike name
> before Casdoor creates the login — Casdoor's own forbidden-list is
> exact-match only. Neither `application-pki.yml` nor the Liquibase seed in
> this repo models `signup-gate`, so a deployment built from **only** this
> repo's local-dev mirror routes `/api/signup` and `/api/login` straight to
> Casdoor and **bypasses the confusable-username gate entirely**. See "Known
> Gaps / TODO" below.

> **`hugmesh-pki` / `scale.hugmesh.net` is correct, not stale (2026-08-05
> correction).** A prior pass (#20) reverted these two identifiers back to
> their pre-rebrand spellings, reasoning that headscale's
> "real tailnet hostname" and the "actual k8s namespace" were still
> unrenamed. That premise is now disproven by every other current source in
> the platform: both direct siblings ([hugmesh-be-gateway](https://github.com/hugmesh/hugmesh-be-gateway)
> and [hugmesh-fe-gateway](https://github.com/hugmesh/hugmesh-fe-gateway))
> run on `hugmesh-backend` / `hugmesh-frontend`; the generator's own ArgoCD
> Application ([`specs/cluster/apps/20-gateway-pki.yaml`](https://github.com/hugmesh/hugmesh-generator/blob/main/specs/cluster/apps/20-gateway-pki.yaml) —
> this README's own cited "deployed source of truth") targets
> `destination.namespace: hugmesh-pki` and `host: scale.hugmesh.net`; and
> headscale's own shipped config
> (`keys-template/headscale/config.yaml` in hugmesh-generator) sets
> `server_url: https://scale.hugmesh.net`, with Casdoor's OIDC client
> registration (`specs/cluster/manifests/casdoor/init-job.yaml`) issuing the
> matching `https://scale.hugmesh.net/oidc/callback` redirect URI. The
> generator's own rebrand (`generator#437`) merged 23 minutes *before* #20, so
> the "still un-renamed" premise was already stale the moment #20 was written.
> Keep this repo's namespace/hostname literals in sync with the generator
> Application above — never revert them to pre-rebrand spellings.

> **SCG-5 route-prefix note (DONE).** Spring Cloud Gateway 5.0 (the
> Spring Boot 4 baseline this fleet now runs) moved static route config from
> `spring.cloud.gateway.routes` to `spring.cloud.gateway.server.webflux.routes`;
> the legacy prefix is **silently ignored and binds zero routes**.
> `application-pki.yml` has been **migrated** to
> `spring.cloud.gateway.server.webflux.routes` (matching the sibling
> be-/fe-/hugmesh-gateway configs), so the standalone/local-dev mirror binds
> its casdoor + headscale routes under SCG-5. The deployed source of truth is the
> Postgres-backed `gateway_routes` rows (seeded by
> [`liquibase/changelog/001-gateway-routes.xml`](liquibase/changelog/001-gateway-routes.xml)
> and rendered by the generator Application), which is loaded by the shared
> image's route loader and is **not** affected by the YAML-prefix move.

## Role gates (inherited from the shared image)

Authorization is enforced by the shared gateway code, not re-implemented here.
After Spring Security authenticates the Casdoor JWT, the per-gateway
external-access gate consults the admin-managed `gateway_route_access` rows for
`hugmesh-pki-gateway` (the `gateway_route_access` table is the source of
truth, applied by the shared `GatewayRouteAccessFilter`). When neither Postgres
nor Cassandra is reachable the gate falls back to the **baked-in canonical
seed** for this tier (so it is never silently fail-open):

| URL pattern | Role |
|-------------|------|
| `/api/auth/**` | `ROLE_USER` |
| `/login/**` | `PUBLIC` (anonymous-allowed) |
| `/.well-known/**` | `PUBLIC` (anonymous-allowed) |

Login and OIDC discovery are deliberately `PUBLIC` so an unauthenticated visitor
can reach the login page and a client can fetch JWKS; the token surface floors
to `ROLE_USER`. Infra/observability paths (`/actuator/**`, …) stay gated to
`GAME-MASTER` / `ADMIN` / `SUPERADMIN` by the shared `SecurityConfig`.

> **`GAME-MASTER` is the games-tier name of the platform `MOD` authority** in
> the canonical `superadmin → admin → mod → user` cascade. The shared image's
> `Roles.isGameMaster()` matches the literal Casdoor role name `GAME-MASTER` /
> `GAME_MASTER` (both spellings), and `SecurityConfig` / the route-access and
> resource-access filters gate and own-domain-bypass on exactly that name — so
> this doc uses `GAME-MASTER` where it is naming what the inherited code emits,
> not a typo for `MOD`.

> **What is provided here vs by the shared image.** The route-access rows and
> the baked-in canonical seed/fallback above are **provided and enforced by the
> shared `hugmesh-gateway` base image** (and CRUD'd at runtime by admin-bff —
> see [Source of truth](#source-of-truth)). THIS repo only seeds the
> `gateway_routes` *route-definition* table
> ([`liquibase/changelog/001-gateway-routes.xml`](liquibase/changelog/001-gateway-routes.xml));
> it does **not** ship any `gateway_route_access` seed of its own.

## How it relates to the gateways / Casdoor / federation / multi-tenancy

- **Casdoor (auth) lives behind this gateway.** This is the door every other
  tier's tokens come from: the be/fe gateways and BFFs validate JWTs *minted*
  by the Casdoor this gateway fronts. The platform runs **one Casdoor per
  brand**, so the cryptographically-validated `iss` claim is the trustworthy
  tenant discriminator.
- **Opaque-session bridge (inherited from the shared image).** The browser
  never holds a Casdoor JWT. login-ui drives a Casdoor OIDC authorization-code
  (PKCE) redirect and user-bff performs the server-side code→token exchange,
  holding per-session JWT custody/refresh; the browser only ever carries an
  **opaque `bff_`-prefixed session**. A pre-security exchange filter in the
  shared gateway code swaps `bff_` → the internal custody JWT before Spring
  Security runs, so this gateway stays a **pure Casdoor-JWT OAuth2 resource
  server** and its JWT validation + role gate run unchanged. This overlay
  inherits that behavior from the base image; nothing about it is configured
  here beyond `CASDOOR_ISSUER_URI`.
- **Issuer-keyed domain scoping (foundation on the shared image).** The shared
  `CasdoorAuthorities` converter stamps every authenticated principal with
  exactly one issuer-derived `DOMAIN_<x>` authority (a non-role authority,
  `DOMAIN_default` on single-issuer clusters), resolved through the
  `DomainRegistry` from the validated issuer — never a user-settable claim. A
  reserved-prefix guard drops any `permissions` entry beginning `ROLE_` /
  `DOMAIN_`, closing the authority-injection / cross-domain-escalation hole.
- **Least-privilege cascade.** `SUPERADMIN > ADMIN > MOD > USER` (the
  games-tier name for `MOD` is `GAME-MASTER` — see the note under
  [Role gates](#role-gates-inherited-from-the-shared-image)). `SUPERADMIN`
  is the only cross-domain bypass (the infra control plane); `ADMIN` /
  `GAME-MASTER` bypass only within their own domain; and any non-superadmin
  whose `DOMAIN_<x>` does not match the request's target brand is hard-denied
  before the grant layer is even consulted. `ROLE_IMAGE_BUILDER` is a separate
  per-domain capability.
- **Multi-brand.** A per-brand instance is named `${BRAND}-pki-gateway`; the
  shared image derives its tier (and the right seed) by the `-pki-gateway`
  suffix, so a runtime-added brand gateway is closed-by-default until its
  Postgres rows load. headscale gives the platform its Tailscale-only federated
  reach for remote modules and the superadmin tailnet console.

## Source of truth

The route table and per-instance config that actually run in the cluster come
from the generator's ArgoCD Application
[`specs/cluster/apps/20-gateway-pki.yaml`](https://github.com/hugmesh/hugmesh-generator/blob/main/specs/cluster/apps/20-gateway-pki.yaml),
which renders the shared `hugmesh-gateway` Helm chart with `instance.name:
hugmesh-pki-gateway` and the pki `routes`. The admin-bff
(`hugmesh-admin-bff-api`) CRUDs the `gateway_route_access` rows. This repo's
`application-pki.yml` and `liquibase/changelog` are the standalone/local-dev
mirror of that same identity; keep them in sync with the generator Application,
which stays the deployed source of truth.

## Known Gaps / TODO

- **This repo's route table is missing the deployed `signup-gate` routes.**
  The generator's ArgoCD Application
  ([`specs/cluster/apps/20-gateway-pki.yaml`](https://github.com/hugmesh/hugmesh-generator/blob/main/specs/cluster/apps/20-gateway-pki.yaml)) —
  the same file this README calls the deployed source of truth for the route
  table — renders **six** routes, not the four in `application-pki.yml` /
  the Liquibase seed: it puts `signup-gate-signup` (`/api/signup`) and
  `signup-gate-login` (`/api/login`) ahead of the `casdoor-*` routes, proxying
  through the pre-signup confusable/uniqueness gate (see the note under
  ["What it routes"](#what-it-routes)). Neither this repo's YAML nor its
  Liquibase seed models `signup-gate`, so they are not just missing a literal
  value here — they are missing two whole routes, and a gateway stood up from
  only this repo's local-dev mirror would let signup/login traffic bypass the
  confusable-name gate. Fixing this needs a `SIGNUP_GATE_URI` env-overridable
  default plus two more route entries/seed rows, mirroring the generator
  Application's ordering (signup-gate routes **before** `casdoor-auth`).
- **Liquibase seed rows can silently drift from `application-pki.yml`.** The
  `gateway_routes` insert statements in
  [`liquibase/changelog/001-gateway-routes.xml`](liquibase/changelog/001-gateway-routes.xml)
  hardcode the Casdoor/headscale URIs as plain literals — Liquibase XML does
  not support Spring's `${VAR:default}` relaxed-binding syntax used in
  `application-pki.yml` — so a `CASDOOR_URI` or `HEADSCALE_URI` env override
  applied at deploy time will **not** propagate to these seeded fallback rows.
  If the defaults in either file are ever changed, the other must be updated
  by hand (see the changeSet comment in that file and the "Source of truth"
  section above).

## CI

The only pipeline is [`.gitlab-ci.yml`](.gitlab-ci.yml): a single `build` stage
that bases off the sibling `hugmesh-gateway` image (`GATEWAY_IMAGE`
build-arg) and pushes this overlay to the in-cluster GitLab registry on the
privileged `hugmesh-x86` runner, only on the default branch or a tag. No keys
are baked in. **CI is GitLab-only** — there are intentionally no GitHub Actions
(`.github/workflows/`) in this repo, in line with the platform's CI = GitLab
ONLY standard.

## Testing

This is a **config-only overlay with no source or tests of its own**. All
gateway behavior — the security filter chain, RBAC, the issuer-keyed
domain-scoping converter, the `bff_`→JWT exchange filter, and the route-access /
resource-access filters (including the `GAME-MASTER`/`ADMIN` own-domain bypass
and the reserved-prefix guard) — is unit- and e2e-tested in
[hugmesh-gateway](https://github.com/hugmesh/hugmesh-gateway).
What this repo contributes is the route config (`application-pki.yml`) and the
`gateway_routes` seed; the meaningful check here is that they stay in sync with
the deployed generator Application (see [Source of truth](#source-of-truth)). An
earlier branch carried Java source and a Playwright/Gherkin e2e suite; those
were removed when the repo was refactored into a pure thin overlay and now live
in the shared gateway repo.

## Current State

- **Ecosystem baseline.** The fleet now runs **Spring Boot 4.0.7 / Spring Cloud
  2025.1.2** on **Java 25 LTS** (`eclipse-temurin:25`), with shared Maven
  artifacts `hugmesh-parent` (metapom
  2.7.0), `hugmesh-commons` (shared security/tenancy, `systems.hugmesh.commons.*`,
  2.7.0) and the new `hugmesh-auth-core` (2.3.0, shared
  account/session/MFA/Casdoor-admin infra; consumed by user-bff and admin-bff
  only — **not** by this gateway). This overlay carries no Java of its own, so
  it inherits the baseline entirely through the shared `hugmesh-gateway`
  base image; there is no Boot-version pin in this repo.
- **Auth model (live this cycle).** Casdoor is the SSO IdP. login-ui runs the
  OIDC authorization-code (PKCE) redirect; **user-bff** performs the
  server-side code→token exchange and holds per-session JWT custody/refresh; the
  browser only ever holds an opaque `bff_` session. The gateway remains a pure
  Casdoor-JWT resource server, with the shared image's pre-security exchange
  filter swapping `bff_` → the internal custody JWT. Login/session/account
  *logic* is being relocated from admin-bff to user-bff (shared core extracted
  to `hugmesh-auth-core`); the account/session **data** stays in
  admin-bff-db, reached by user-bff over mTLS + `X-Internal-Token`. None of this
  is configured in this repo — it is named here only so the docs match the
  current platform reality and do **not** imply login lives in admin-bff.
- **Authorization data path.** `gateway_route_access` (Postgres) is the source
  of truth, applied by the shared `GatewayRouteAccessFilter`; ADMIN /
  GAME-MASTER bypass within their own domain, SUPERADMIN cross-domain.
- **SCG-5 route namespace.** SCG 5.0 moved static route config to
  `spring.cloud.gateway.server.webflux.routes`; `application-pki.yml` has been
  migrated off the legacy `spring.cloud.gateway.routes` prefix (see the note
  under [What it routes](#what-it-routes)). The deployed Postgres-backed
  `gateway_routes` path is unaffected.
- **Cloudflare** fronts prod (this gateway stays internal-only behind it).
- Everything in the sections above is shipped on `main`.

## Roadmap

State legend: ✅ shipped (on `main`) · 🚧 in-flight (open branch/PR, not merged) ·
🔭 planned (design only). Everything documented above is ✅ shipped. The remaining
items below are **not** yet enforced by this overlay — either because they have
not landed yet (🚧 / 🔭, arriving via the shared `hugmesh-gateway` image and
admin-bff changelogs, which this repo inherits when those ship), or because they
**have** landed upstream and are inherited here but **ship dark behind a
default-off flag** (marked ✅, with the flag named in the bullet).

- ✅ **SCG-5 static-route prefix rename.** `application-pki.yml` migrated from
  `spring.cloud.gateway.routes` to `spring.cloud.gateway.server.webflux.routes`
  so the static config binds routes under Boot 4 / SCG 5 (matches the sibling
  be-/fe-/hugmesh-gateway configs). The deployed Postgres `gateway_routes`
  path already worked; this fixes the image's baked static config too.
- ✅ **`docker build .` profile activation.** The `Dockerfile` now sets
  `SPRING_PROFILES_ACTIVE=pki` in addition to `SPRING_CONFIG_ADDITIONAL_LOCATION`,
  so `application-pki.yml` (a profile-specific `application-{profile}.yml`
  document) actually loads and the built image carries the casdoor/headscale
  routes instead of falling back to the base image's packaged `api-default`
  route. Same bug class as the fleet-wide gap already fixed for the
  local-compose stack and the Helm chart (`generator#261` / `generator#275`).
- 🚧 **SSO end-to-end + e2e.** The Casdoor OIDC → user-bff custody-JWT →
  opaque `bff_` session flow (with the gateway's `bff_`→JWT exchange filter) is
  being brought up live end-to-end and covered by e2e. The gateway behavior is
  in the shared image; this overlay inherits it.
- ✅ **MFA-required route enforcement — shipped upstream, ships dark.** The
  enforcement machinery has landed and this overlay already inherits it; it is
  **feature-flagged off**, not absent. `gateway_route_access` carries a
  fail-closed `mfa_required` column (added by admin-bff Liquibase
  `001-gateway-route-access.xml`, `NOT NULL DEFAULT false`, so every existing row
  is `false`), and a deciding grant on a row with `mfa_required=true`
  additionally demands the `MFA_SATISFIED` marker — absent marker means
  **denied**. The D8 privileged-MFA floor sits *above* the `SUPERADMIN`
  cross-domain bypass, so even a `SUPERADMIN` reaching an `mfa_required` surface
  without a fresh second factor is denied.

  `MFA_SATISFIED` is **not** a role and **not** a permission: it is a non-role
  marker derived solely from the **BFF-written, freshness-validated
  `mfa_satisfied_at` JWT-Custom claim** (epoch-millis as a string, stamped by the
  BFF on a successful MFA challenge). A stamp is fresh iff
  `0 <= now - ts <= app.mfa.freshness-window` (default `600000`, 10 minutes); a
  **future** timestamp is rejected as clock-skew/forged-future. A forged
  `permissions:["MFA_SATISFIED"]` is dropped by the reserved-authority-prefix
  guard, and the missing `ROLE_` prefix means the marker can never accidentally
  satisfy a role check.

  **MFA is still not enforced on identity-tier routes today**, because both
  `app.mfa.enabled` and `app.mfa.enforce-privileged-floor` default `false` — when
  off, the `mfa_required` column is inert. What remains is the platform-wide half:
  MFA enrollment / step-up (WebAuthn + TOTP + recovery, per-role defaults; the
  admin-bff `150`–`152` substrate/factors/WebAuthn changelogs are merged), the
  admin-bff-managed `mfa_required` rows for the identity-tier routes, and flipping
  the two flags. Nothing in this overlay changes either way.
- ✅ **Continuous-OWASP security CI stage — DECIDED: this overlay is EXEMPT.**
  The platform pen-test standard adds a shared `.gitlab-ci.yml` `include`
  (ZAP DAST + Dependency-Check SCA + nuclei + k6) to app/service repos.
  Decision (2026-07 hygiene pass): gateway-config overlays like this repo are
  **exempted**, for two verifiable reasons. (1) This repo contains no
  dependency manifest and no application code — only a `Dockerfile`,
  `application-pki.yml`, and Liquibase changelogs — so a Dependency-Check SCA
  job here would scan nothing and report a vacuous green. (2) Every byte of
  runtime behavior comes from the shared `hugmesh-gateway` image, whose own
  repo carries the full pen-test stage jobs (`pentest:sca` + `pentest:dast`
  extending the centralized generator template — see that repo for the current
  execution status of its DAST leg); a DAST run from this repo would re-scan
  that sibling artifact, double-counting coverage without testing anything this
  overlay adds. **Revisit trigger:** if this overlay ever gains its own
  dependency manifest or app surface (anything beyond config/changelogs), it
  MUST adopt the shared `include` like the app/service repos.
