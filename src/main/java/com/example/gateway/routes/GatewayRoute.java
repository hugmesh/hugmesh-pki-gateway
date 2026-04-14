package com.example.gateway.routes;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

/**
 * Row in the {@code gateway_routes} table.
 *
 * <p>The JSONB/JSON columns ({@code predicates}, {@code filters}, {@code metadata})
 * are modeled as {@link String} fields carrying raw JSON. For Postgres, a
 * {@code Converter<io.r2dbc.postgresql.codec.Json, String>} registered in
 * {@code R2dbcConfig} unwraps the native JSONB return. For H2 (local profile),
 * the columns are declared as {@code VARCHAR} so the driver returns them as
 * {@code String} directly without conversion.
 */
@Table("gateway_routes")
public record GatewayRoute(
        @Id String id,
        String uri,
        String predicates,
        String filters,
        @Column("route_order") int routeOrder,
        String metadata,
        boolean enabled,
        @Column("updated_at") OffsetDateTime updatedAt
) {
}
