# Project notes for Claude Code

Guidance for future Claude Code sessions working in this repo. Keep this file
short and factual — prefer the README for human-oriented documentation.

## What this is

`snapdragon-id-gateway` — a fully reactive Spring Cloud Gateway that loads its
route table from Postgres over R2DBC and integrates with Casdoor as an OAuth2
identity provider. Single Maven module, single deployable jar.

## Stack invariants

- **Java 21, Spring Boot 3.4.x, Spring Cloud 2024.0.x.** Don't bump a
  generation without checking the Spring Cloud compatibility matrix.
- **Reactive only at runtime.** WebFlux + R2DBC + Reactor. **No JDBC on the
  app's main classpath.** JDBC appears in exactly two places and nowhere
  else:
  1. The `liquibase/liquibase` Docker image (init container against primary
     Postgres).
  2. The test classpath (`liquibase-core` + `org.postgresql:postgresql` as
     `<scope>test</scope>` deps), where the `PostgresInitializer` applies
     the changelog against a Testcontainers Postgres via JDBC.
  Any proposal that adds JDBC to `<scope>compile</scope>` is wrong — fix
  the underlying need a different way.
- **Read-only access to `gateway_routes` is structural.** Three enforcement
  layers, don't compromise any of them:
  1. The gateway registers only `RouteDefinitionLocator` (read-only), never
     `RouteDefinitionRepository` (read-write). A writer bean auto-wires an
     HTTP write endpoint, which we specifically do not want.
  2. The runtime R2DBC user is `gateway_reader`, a `SELECT`-only role
     created by Liquibase changeset `002-create-reader-role`.
  3. The gateway connects to the streaming replica (`pg-replica`), not
     the primary. A streaming replica rejects writes at the Postgres level.
  If someone asks for a "just this once" admin write path from the app,
  the answer is no — write paths go through Liquibase changelogs.
- **One profile must be active.** `local` or `casdoor`. The OAuth2
  `ReactiveClientRegistrationRepository` is a profile-gated `@Bean` in
  `OAuth2ClientConfig`; without a profile, `oauth2Login()` fails to wire
  and context fails to start. `application.yml` intentionally has no
  `spring.security.oauth2.client.*` — the bean is the single source of
  truth.

## Profiles

| Profile | DB | OAuth2 provider | When used |
|---|---|---|---|
| `local` | H2 in-memory, schema + seed from `src/main/resources/sql/` | In-process `no.nav.security:mock-oauth2-server` on a random port | Standalone jar, IDE, integration tests (`@ActiveProfiles("local")`) |
| `casdoor` | R2DBC to `pg-replica` as `gateway_reader` | Real OIDC discovery from `CASDOOR_ISSUER_URI` | `docker-compose.yml` |

The `local` profile duplicates the schema across
`liquibase/changelog/*.xml` (Postgres, JSONB) and
`src/main/resources/sql/h2-schema.sql` (H2, VARCHAR). When you change the
schema in one place, change it in the other — a Liquibase-only approach
against both databases would require either H2-specific dialect variants
or dropping JSONB, neither of which is worth the complexity.

## Build + test

```
./mvnw clean test                                      # unit + integration
./mvnw -DskipTests package                             # build jar
sg docker -c "docker compose up -d --build"            # full stack
sg docker -c "docker compose down -v"                  # tear down
```

Note the `sg docker -c "..."` wrapper: on this host, the interactive shell's
Docker group membership isn't set (WSL2 without systemd using manual
`service docker start`). Wrap any docker/compose command in `sg docker -c`
so the shell picks up the group. The bare `docker` invocation will fail
with "permission denied on /var/run/docker.sock".

## Environment

- **DigitalOcean Droplet running Ubuntu 24.04.** This matters: port
  bindings on `0.0.0.0` ARE reachable from the public internet in
  principle (the Droplet's public IP is on `eth0`), subject to the host
  firewall. There is **no WSL2-style isolation**.
- **`ufw` is active on this host with a restrictive default**. As of
  writing it allows only `47829/tcp`, `80/tcp`, and `443/tcp` inbound —
  everything else is dropped. That's load-bearing for why binding random
  high ports to `0.0.0.0` hasn't led to exposure incidents so far. Do
  NOT assume this is permanent; check `sudo ufw status` before relying
  on it, and prefer loopback binding in compose regardless (defense in
  depth).
- **The Droplet already runs something on 80/443.** Don't assume those
  ports are free. Use the high ports in docker-compose (see below).
- **Docker Engine is installed natively** (see `scripts/install-docker.sh`).
  The current shell's group membership may not include `docker` — wrap
  docker/compose commands in `sg docker -c "..."` so the shell picks up
  the group.
- **No sudo from the Bash tool.** The harness blocks sudo. If a task
  genuinely needs it (package installs, system service control, querying
  `ufw`/`iptables` filter rules), hand the user the exact commands to
  run instead of attempting them.
- **Port 5432 may be taken on the host** by a system Postgres. The compose
  file publishes the primary at `127.0.0.1:15432` and the replica at
  `127.0.0.1:15433` to avoid conflicts. Don't change these back to 5432
  without checking the host first.
- **Dev-facing service ports bind to `127.0.0.1` by default**, not
  `0.0.0.0`, specifically because this is a publicly-reachable Droplet
  and we do not want random scanners hitting Casdoor's default
  `built-in/admin` / `123` login during a verification run. The gateway
  and Casdoor bindings are env-var switchable:
  `GATEWAY_BIND_HOST` and `CASDOOR_BIND_HOST` in `.env` (default
  `127.0.0.1`). Set either to `0.0.0.0` to expose on all interfaces.
  Pg nodes are hard-coded loopback — there's no use case for external
  access to them. If you need remote access from a workstation, prefer
  `ssh -L 8080:127.0.0.1:8080 droplet` over flipping the bind host.

## Known gotchas (with workarounds already in place)

1. **Testcontainers 1.21.x ships a docker-java client that defaults to
   Docker API version 1.32**, which Docker 29+ rejects (minimum is 1.44).
   `src/test/resources/docker-java.properties` pins `api.version=1.47` as
   a workaround. Upgrade when Testcontainers 2.0.x hits Maven Central.
   See [#11210](https://github.com/testcontainers/testcontainers-java/issues/11210).
2. **Liquibase runs as the `postgres` superuser**, not `gateway_admin`,
   because changeset `002-create-reader-role` needs `CREATEROLE`. The
   Bitnami image exposes the superuser password via
   `POSTGRESQL_POSTGRES_PASSWORD`, which the compose file wires to
   `POSTGRES_ADMIN_PASSWORD`.
3. **Liquibase CLI argument parsing in 4.29-alpine is finicky.** Pass
   changelog parameters via `JAVA_OPTS=-Dkey=value`, not
   `--changelog-parameter=key=value` (rejected as unknown flag) or
   `LIQUIBASE_COMMAND_CHANGELOG_PARAMETERS=key=value` (ClassCastException
   because the env var path expects a structured map, not a string).
4. **Casdoor returns its `issuer` without a trailing slash.** Spring
   Security does strict equality against the URL used for discovery, so
   `CASDOOR_ISSUER_URI` must match exactly — `http://casdoor:8000`, not
   `http://casdoor:8000/`. A mismatch throws
   `ClientRegistrations.fromIssuerLocation` at bean creation.
5. **`bitnami/postgresql:16` was removed from Docker Hub's free tier**
   during Bitnami's 2025 re-org. Versioned tags live under
   `bitnamilegacy/postgresql:17.6.0` (or newer). Don't switch back to
   the plain `bitnami` namespace.
6. **`casbin/casdoor-all-in-one` is Debian 13 without `wget`/`curl`.**
   The container healthcheck uses `bash -c ': >/dev/tcp/localhost/8000'`
   to probe the HTTP port via bash's pseudo-device. Don't "fix" this
   back to a wget/curl-based probe.
7. **Spring Boot's R2DBC autoconfig doesn't expose `R2dbcDialect` before
   user-provided `R2dbcCustomConversions` beans are created.** The
   `R2dbcConfig` bean takes `ConnectionFactory` as its parameter and
   resolves the dialect with `DialectResolver.getDialect(connectionFactory)`
   inside the method body.

## Conventions

- **Test classes named `*IT.java` are integration tests.** They use
  Testcontainers and are auto-skipped without Docker via
  `@Testcontainers(disabledWithoutDocker = true)`. Unit tests are
  `*Test.java` (Surefire default naming).
- **All integration tests activate `@ActiveProfiles("local")`** to get the
  in-process mock OAuth2 server. `PostgresInitializer` then overrides
  `spring.r2dbc.*` to point at Testcontainers Postgres and sets
  `spring.sql.init.mode=never` to suppress the local-profile H2 schema
  script (the Testcontainers DB already has the Liquibase-applied schema).
- **`GatewayRoute`, `GatewayRouteRepository`, and the `gateway_routes`
  table retain the "Gateway" name** on purpose — they describe the data
  model for the route table, not the application. Don't rename them to
  "Snapdragon…" during cosmetic cleanups.
- **Use `git mv` for file moves**, not `rm + add`, so rename tracking
  stays intact.
- **Commit-trailer co-author line**: `Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>`
  (adjust if running a different model). The system prompt's template
  hardcodes Sonnet; override it with whatever model is actually active.

## What NOT to add without discussion

- **A mock OAuth2 server service in `docker-compose.yml`.** The design
  intentionally requires a real Casdoor (or `casbin/casdoor-all-in-one`)
  for the dockerized path. The in-process mock is a `local`-profile-only
  concern. See `.claude/projects/.../memory/feedback_require_real_deps.md`
  for the history on this.
- **An admin HTTP endpoint for writing routes.** See "read-only is
  structural" above.
- **JDBC dependencies to the main classpath.** See "reactive only" above.
- **CI/CD workflows without asking first.** This repo doesn't have CI
  yet; the owner hasn't specified which provider (GitHub Actions,
  something else) or what checks to run.
