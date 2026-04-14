-- Seed data for the `local` H2 profile.
-- Mirrors liquibase/changelog/003-seed-routes.xml — same route definition so
-- a developer running the `local` profile sees the same `example-route` that
-- shows up in docker-compose against real Postgres.
--
-- MERGE (not INSERT) so re-runs of spring.sql.init.mode=always are idempotent.

MERGE INTO gateway_routes (id, uri, predicates, filters, route_order, metadata, enabled) KEY (id) VALUES
  ('example-route',
   'https://httpbin.org',
   '[{"name":"Path","args":{"_genkey_0":"/get/**"}}]',
   '[{"name":"TokenRelay","args":{}}]',
   0,
   '{}',
   TRUE);
