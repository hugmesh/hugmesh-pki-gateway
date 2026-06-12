# snapdragon-pki-gateway

Per-node-type instance of the Snapdragon Spring Cloud Gateway — the **identity
tier** front door. It fronts Casdoor (auth / login / OIDC) and headscale (the
VPN/tailnet control plane). Internal-only (ingress class `nginx-internal`),
never internet-facing, and it validates the internal Casdoor JWT — no external
login is exposed.

It is **the same image and code as
[snapdragon-gateway](https://github.com/Snapdragon-Systems/snapdragon-gateway)**.
Like its [be-](https://github.com/Snapdragon-Systems/snapdragon-be-gateway) and
[fe-gateway](https://github.com/Snapdragon-Systems/snapdragon-fe-gateway)
siblings this repo is a **thin overlay**: only the `SPRING_APPLICATION_NAME`, the
Postgres-backed route set (Liquibase), and the downstream URIs differ. The image
builds `FROM` the shared gateway image so every device runs identical, audited
gateway code — the same security filter chain, RBAC, domain-scoping converter,
and Cassandra-cached revocation / route-access / URL registry.

```bash
# bases off the sibling snapdragon-gateway image (GATEWAY_IMAGE build-arg)
docker build -t snapdragon-systems/pki-gateway:dev .
```

## Tier & namespace

| | |
|---|---|
| **Tier** | identity / PKI |
| **Namespace** | `snapdragon-pki` (downstreams resolve at `*.snapdragon-pki.svc.cluster.local`) |
| **`SPRING_APPLICATION_NAME`** | `snapdragon-pki-gateway` (set in the `Dockerfile`; selects this instance's route-access rows) |
| **Ingress** | `nginx-internal` — cluster-internal only, never internet-facing |
| **Sibling gateways** | `snapdragon-be-gateway` (API + matrix + mastodon), `snapdragon-fe-gateway` (host + remotes) |

## What it routes

The route table is config-driven (`application-pki.yml`, mirrored into the
`gateway_routes` Liquibase seed). All four routes are identity-tier
downstreams in the `snapdragon-pki` namespace:

| Route id | Predicate | Downstream | Purpose |
|----------|-----------|------------|---------|
| `casdoor-auth` | `Path=/api/auth/**` | Casdoor `:8000` | OAuth2 / token endpoints |
| `casdoor-login` | `Path=/login/**` | Casdoor `:8000` | hosted login UI |
| `casdoor-oidc` | `Path=/.well-known/**` | Casdoor `:8000` | OIDC discovery / JWKS |
| `headscale` | `Host=scale.snapdragon.systems` | headscale `:8080` | tailnet / VPN control plane |

`CASDOOR_URI`, `CASDOOR_ISSUER_URI`, and `HEADSCALE_URI` are env-overridable
(defaults point at the in-namespace services). `CASDOOR_ISSUER_URI` is the
issuer the OAuth2 resource server validates JWTs against.

## Role gates (inherited from the shared image)

Authorization is enforced by the shared gateway code, not re-implemented here.
After Spring Security authenticates the Casdoor JWT, the per-gateway
external-access gate consults the admin-managed `gateway_route_access` rows for
`snapdragon-pki-gateway`. When neither Postgres nor Cassandra is reachable the
gate falls back to the **baked-in canonical seed** for this tier (so it is never
silently fail-open):

| URL pattern | Role |
|-------------|------|
| `/api/auth/**` | `ROLE_USER` |
| `/login/**` | `PUBLIC` (anonymous-allowed) |
| `/.well-known/**` | `PUBLIC` (anonymous-allowed) |

Login and OIDC discovery are deliberately `PUBLIC` so an unauthenticated visitor
can reach the login page and a client can fetch JWKS; the token surface floors
to `ROLE_USER`. Infra/observability paths (`/actuator/**`, …) stay gated to
`GAME-MASTER` / `ADMIN` / `SUPERADMIN` by the shared `SecurityConfig`.

## How it relates to the gateways / Casdoor / federation / multi-tenancy

- **Casdoor (auth) lives behind this gateway.** This is the door every other
  tier's tokens come from: the be/fe gateways and BFFs validate JWTs *minted*
  by the Casdoor this gateway fronts. The platform runs **one Casdoor per
  brand**, so the cryptographically-validated `iss` claim is the trustworthy
  tenant discriminator.
- **Issuer-keyed domain scoping (foundation on the shared image).** The shared
  `CasdoorAuthorities` converter stamps every authenticated principal with
  exactly one issuer-derived `DOMAIN_<x>` authority (a non-role authority,
  `DOMAIN_default` on single-issuer clusters), resolved through the
  `DomainRegistry` from the validated issuer — never a user-settable claim. A
  reserved-prefix guard drops any `permissions` entry beginning `ROLE_` /
  `DOMAIN_`, closing the authority-injection / cross-domain-escalation hole.
- **Least-privilege cascade.** `SUPERADMIN > ADMIN`/`MOD`/`USER`; `SUPERADMIN`
  is the only cross-domain bypass (the infra control plane), `ADMIN` /
  `GAME-MASTER` bypass only within their own domain, and any non-superadmin
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
[`specs/cluster/apps/20-gateway-pki.yaml`](https://github.com/Snapdragon-Systems/snapdragon-generator/blob/main/specs/cluster/apps/20-gateway-pki.yaml),
which renders the shared `snapdragon-gateway` Helm chart with `instance.name:
snapdragon-pki-gateway` and the pki `routes`. The admin-bff
(`snapdragon-admin-bff-api`) CRUDs the `gateway_route_access` rows. This repo's
`application-pki.yml` and `liquibase/changelog` are the standalone/local-dev
mirror of that same identity; keep them in sync with the generator Application,
which stays the deployed source of truth.
