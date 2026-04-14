# snapdragon-id-gateway

A fully reactive Spring Cloud Gateway that loads its route table from Postgres
over R2DBC, enforces read-only access to the database at the topology level,
and integrates with Casdoor as an OAuth2 identity provider.

## What's in the box

- **Spring Cloud Gateway** (Spring Boot 3.4.x, Spring Cloud 2024.0.x, Java 21)
- **Fully reactive stack** — WebFlux, R2DBC, Reactor. No JDBC on the app
  classpath at runtime.
- **Postgres primary + streaming replica**, both `bitnamilegacy/postgresql:17.6.0`,
  wired in `docker-compose.yml`. The app connects only to the replica as a
  `SELECT`-only role; writes happen through Liquibase against the primary.
- **Liquibase** runs as a one-shot init container against the primary at stack
  startup, creates the `gateway_routes` table, the `gateway_reader` role, and
  seeds an example route.
- **Casdoor** (via `casbin/casdoor-all-in-one`) runs alongside the gateway as
  the OAuth2 authorization server. The gateway fetches OIDC discovery at
  startup via Spring Security's `ClientRegistrations.fromIssuerLocation`.
- **Profile-gated OAuth2 wiring**:
  - `casdoor` — real Casdoor, real OIDC discovery, fails fast on unreachable
    issuer. Used by docker-compose.
  - `local` — in-process [`MockOAuth2Server`](https://github.com/navikt/mock-oauth2-server)
    on a random port + H2 in-memory database with schema/seed matching
    Postgres. No external dependencies.
- **Dynamic route refresh**: a `@Scheduled` publisher fires
  `RefreshRoutesEvent` every `GATEWAY_ROUTES_REFRESH_INTERVAL` (default 30s);
  `/actuator/gateway/refresh` triggers a manual refresh.

## Architecture

```
                  ┌──────────────────────────────────────────────┐
                  │           docker-compose network             │
  client ──JWT──► │  ┌─────────────────────┐                     │
                  │  │ gateway             │   TokenRelay ───►   │
                  │  │  WebFlux + Gateway  │                     │
                  │  │  OAuth2 client      │                     │
                  │  │  RouteDefinitionLoc │                     │
                  │  │  (READ-ONLY)        │                     │
                  │  └──┬───────────┬──────┘                     │
                  │     │           │ OIDC discovery             │
                  │     │           ▼                            │
                  │     │      ┌────────┐                        │
                  │     │      │ casdoor│ (all-in-one, MySQL     │
                  │     │      └────────┘  bundled)              │
                  │     │ R2DBC (SELECT-only, replica only)      │
                  │     ▼                                        │
                  │  ┌─────────┐  ┌─────────┐                    │
                  │  │ pg-     │──► pg-     │ ◄── gateway reads  │
                  │  │ primary │  │ replica │     from here      │
                  │  └────▲────┘  └─────────┘                    │
                  │       │                                      │
                  │  ┌────┴─────┐                                │
                  │  │ liquibase│ writes schema + SEED routes    │
                  │  │ (jdbc)   │ to primary, then exits.        │
                  │  └──────────┘                                │
                  └──────────────────────────────────────────────┘
```

Writes to `gateway_routes` are blocked at three layers:
1. **API surface** — the app only implements `RouteDefinitionLocator`
   (read-only), never `RouteDefinitionRepository` (read-write).
2. **Database permissions** — the `gateway_reader` role has `SELECT` only.
3. **Physical topology** — the gateway connects to the streaming replica,
   which rejects writes regardless of role.

## Quick start — local profile (no Docker)

Runs the app as a standalone jar with an in-memory H2 database and an
in-process mock OAuth2 server. Zero external dependencies.

```
./mvnw -DskipTests package
java -jar target/gateway-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

Then:

```
curl http://localhost:8080/actuator/health                      # → {"status":"UP"}
curl -v http://localhost:8080/get/foo                           # → 302 /oauth2/authorization/casdoor
```

The `MockOAuth2Server` picks a random port at startup. Its URL is printed
in the log line `Started in-process MockOAuth2Server for local profile at ...`
and used by `OAuth2ClientConfig#localClientRegistrationRepository` to wire a
`ClientRegistration` pointing at a real OIDC provider (the mock). A browser
login flow initiated against the mock will actually complete.

## Quick start — full dockerized stack

Bring up `pg-primary`, `pg-replica`, `liquibase` (one-shot), `casdoor`, and
`gateway` together:

```
cp .env.example .env         # edit passwords as desired
docker compose up --build
```

Wait for all five containers to reach `healthy` (Casdoor takes ~30s on first
boot because the bundled MySQL initializes), then:

```
curl http://localhost:8080/actuator/health           # → {"status":"UP"}
curl -v http://localhost:8080/get/foo                # → 302 /oauth2/authorization/casdoor
```

Casdoor admin UI: <http://localhost:8000> — default login `built-in/admin`
password `123`. Create an application with `client_id=gateway-demo-client`,
`client_secret=gateway-demo-secret` (matching `.env`) and add a redirect URI
`http://localhost:8080/login/oauth2/code/casdoor` to enable the full browser
login flow.

## Spring profiles

| Profile | OAuth2 client | Database | Typical activation |
|---|---|---|---|
| `local` | In-process `MockOAuth2Server` on random port | H2 in-memory, schema + seed from `spring.sql.init` | Standalone jar, IDE |
| `casdoor` | Real OIDC discovery from `$CASDOOR_ISSUER_URI` | R2DBC to `pg-replica` (SELECT-only) | `docker-compose.yml` |

Exactly one of the profiles MUST be active — the OAuth2 client registration
repository bean is profile-gated, and Spring Security's `oauth2Login()`
fails to wire without it.

## Environment variables

See `.env.example` for the full list. Summary:

| Var | Required by | Purpose |
|---|---|---|
| `POSTGRES_ADMIN_PASSWORD` | docker-compose | Superuser + `gateway_admin` password on both pg nodes |
| `POSTGRES_REPLICATION_PASSWORD` | docker-compose | Streaming replication user password |
| `GATEWAY_READER_PASSWORD` | docker-compose | Password Liquibase sets on the `gateway_reader` role, also the R2DBC password the gateway uses |
| `CASDOOR_ISSUER_URI` | gateway container | Casdoor issuer — gateway calls `${issuer}/.well-known/openid-configuration` at startup. Must match what Casdoor reports in its discovery response (e.g. `http://casdoor:8000`, no trailing slash). |
| `CASDOOR_CLIENT_ID` | gateway container | Casdoor application client id |
| `CASDOOR_CLIENT_SECRET` | gateway container | Casdoor application client secret |
| `GATEWAY_ROUTES_REFRESH_INTERVAL` | gateway container | How often the scheduled publisher fires `RefreshRoutesEvent` (default `30s`) |

## Actuator endpoints

All mounted at `/actuator`, permitted without authentication for `health`
and `info`:

- `/actuator/health` — up/down + per-indicator details (including the
  custom `routes` indicator)
- `/actuator/info`
- `/actuator/gateway/routes` — current in-memory route list
- `/actuator/gateway/refresh` — trigger immediate `RefreshRoutesEvent`

## Tests

```
./mvnw clean test
```

Runs:

- **Unit**: `RouteMapperTest` — JSON → `RouteDefinition` conversion,
  including edge cases.
- **Integration** (Testcontainers Postgres, auto-skipped without Docker):
  - `GatewayApplicationTests` — context loads against real Postgres.
  - `DbRouteDefinitionLocatorIT` — Liquibase-applied schema + seed,
    `DbRouteDefinitionLocator` returns the seed route via R2DBC.
  - `SecurityConfigTest` — unauthenticated request redirects to
    `/oauth2/authorization/casdoor` via the `local` profile's mock OIDC.

## Known issues

### Testcontainers vs Docker Engine 29

Testcontainers 1.21.x bundles a docker-java client that defaults to API
version 1.32, which Docker 29 rejects (minimum accepted API is 1.44).
`src/test/resources/docker-java.properties` pins the API version to 1.47 as
a workaround until Testcontainers 2.0.2+ is available and adopted. See
[testcontainers/testcontainers-java#11210](https://github.com/testcontainers/testcontainers-java/issues/11210).

### Liquibase needs superuser

Changeset `002-create-reader-role` uses `CREATE ROLE`, which requires a
role with the `CREATEROLE` attribute. The compose `liquibase` service
therefore connects as the `postgres` superuser (exposed by the Bitnami
image via `POSTGRESQL_POSTGRES_PASSWORD`). Running Liquibase as
`gateway_admin` would fail with "permission denied to create role".

### Casdoor issuer URI trailing slash

Spring Security performs strict equality between the URL used to fetch OIDC
discovery and the `issuer` field in the discovery response. Casdoor reports
its issuer without a trailing slash (e.g. `http://casdoor:8000`), so
`CASDOOR_ISSUER_URI` must also be set without a trailing slash. A mismatch
causes `ClientRegistrations.fromIssuerLocation` to throw at bean creation,
and the gateway fails to start.
