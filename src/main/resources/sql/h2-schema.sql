-- H2 schema for the `local` Spring profile.
--
-- Mirrors the structure defined by the Liquibase changelog under
-- liquibase/changelog/001-create-gateway-routes.xml. H2 has no JSONB, so the
-- three JSON columns are declared VARCHAR and the application treats them as
-- raw JSON strings (same as on Postgres, via an R2dbcCustomConversions
-- Json→String reading converter — see R2dbcConfig).

CREATE TABLE IF NOT EXISTS gateway_routes (
  id           VARCHAR(255)    PRIMARY KEY,
  uri          VARCHAR(2048)   NOT NULL,
  predicates   VARCHAR(8192)   NOT NULL,
  filters      VARCHAR(8192)   NOT NULL DEFAULT '[]',
  route_order  INT             NOT NULL DEFAULT 0,
  metadata     VARCHAR(8192)   NOT NULL DEFAULT '{}',
  enabled      BOOLEAN         NOT NULL DEFAULT TRUE,
  updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS gateway_routes_enabled_order_idx
  ON gateway_routes (enabled, route_order);
