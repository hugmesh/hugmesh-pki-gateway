# snapdragon-pki-gateway

Per-node-type instance of the Snapdragon Spring Cloud Gateway. Identity-tier
front door — Casdoor (auth/login/OIDC) + headscale (VPN control). Internal-only
(ingress class `nginx-internal`); never internet-facing.

It is **the same image and code as [snapdragon-gateway](https://github.com/Snapdragon-Systems/snapdragon-gateway)** — only the
`SPRING_APPLICATION_NAME`, the Postgres-backed route set (Liquibase), and the
downstream URIs differ. Build `FROM` the shared gateway image so every device
runs identical, audited gateway code (the 4 shared WebFilters, RBAC, Cassandra
revocation/URL registry). Validates the internal Casdoor JWT; no external login
is exposed.

## Source of truth

The route table and per-instance config that actually run in the cluster come
from the generator's ArgoCD Application
[`specs/cluster/apps/20-gateway-pki.yaml`](https://github.com/Snapdragon-Systems/snapdragon-generator/blob/main/specs/cluster/apps/20-gateway-pki.yaml),
which renders the shared `snapdragon-gateway` Helm chart with `instance.name:
snapdragon-pki-gateway` and the pki `routes`. This repo's `application-pki.yml`
and `liquibase/changelog` are the standalone/local-dev mirror of that same
identity; keep them in sync with the generator Application, which stays the
deployed source of truth.

```bash
docker build -t snapdragon-systems/pki-gateway:dev .
```
