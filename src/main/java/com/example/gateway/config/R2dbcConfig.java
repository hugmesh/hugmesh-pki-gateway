package com.example.gateway.config;

import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.DialectResolver;
import org.springframework.data.r2dbc.dialect.R2dbcDialect;

import java.util.List;

/**
 * Registers a custom R2DBC reading converter that unwraps Postgres JSONB values
 * (returned by r2dbc-postgresql as {@link Json}) into plain {@link String}, so
 * the {@code GatewayRoute} entity can hold the JSON payloads as strings.
 *
 * <p>For the {@code local} profile (H2), the JSON columns are declared as
 * {@code VARCHAR} and come back as {@code String} directly; the converter
 * below is never invoked on that path.
 *
 * <p>The {@link R2dbcDialect} is resolved from {@link ConnectionFactory} inside
 * the bean method rather than injected as a parameter, because Spring Boot's
 * {@code R2dbcDataAutoConfiguration} exposes the dialect bean in a conditional
 * path that runs AFTER this config when this config's bean overrides the
 * default {@code r2dbcCustomConversions}, creating a chicken-and-egg.
 */
@Configuration
public class R2dbcConfig {

    @Bean
    public R2dbcCustomConversions r2dbcCustomConversions(ConnectionFactory connectionFactory) {
        R2dbcDialect dialect = DialectResolver.getDialect(connectionFactory);
        return R2dbcCustomConversions.of(dialect, List.of(new JsonToStringConverter()));
    }

    @ReadingConverter
    static class JsonToStringConverter implements Converter<Json, String> {
        @Override
        public String convert(Json source) {
            return source.asString();
        }
    }
}
