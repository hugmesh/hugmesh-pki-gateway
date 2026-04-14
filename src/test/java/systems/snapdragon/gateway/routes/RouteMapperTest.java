package systems.snapdragon.gateway.routes;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-unit tests for {@link RouteMapper}. No Spring context, no database — verifies the
 * JSON string → RouteDefinition conversion, including edge cases that shouldn't be in
 * the integration test.
 */
class RouteMapperTest {

    private RouteMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new RouteMapper(new ObjectMapper());
    }

    @Test
    void mapsSeedExampleRoute() {
        GatewayRoute row = new GatewayRoute(
                "example-route",
                "https://httpbin.org",
                "[{\"name\":\"Path\",\"args\":{\"_genkey_0\":\"/get/**\"}}]",
                "[{\"name\":\"TokenRelay\",\"args\":{}}]",
                0,
                "{}",
                true,
                OffsetDateTime.now()
        );

        RouteDefinition rd = mapper.toRouteDefinition(row);

        assertThat(rd.getId()).isEqualTo("example-route");
        assertThat(rd.getUri()).hasToString("https://httpbin.org");
        assertThat(rd.getOrder()).isZero();
        assertThat(rd.getPredicates()).hasSize(1);
        assertThat(rd.getPredicates().get(0).getName()).isEqualTo("Path");
        assertThat(rd.getPredicates().get(0).getArgs())
                .containsEntry("_genkey_0", "/get/**");
        assertThat(rd.getFilters()).hasSize(1);
        assertThat(rd.getFilters().get(0).getName()).isEqualTo("TokenRelay");
        assertThat(rd.getFilters().get(0).getArgs()).isEmpty();
        assertThat(rd.getMetadata()).isEmpty();
    }

    @Test
    void handlesMultiplePredicatesAndFilters() {
        GatewayRoute row = new GatewayRoute(
                "multi",
                "https://api.example.com",
                "["
                        + "{\"name\":\"Path\",\"args\":{\"_genkey_0\":\"/a/**\"}},"
                        + "{\"name\":\"Method\",\"args\":{\"_genkey_0\":\"GET\"}}"
                        + "]",
                "["
                        + "{\"name\":\"AddRequestHeader\",\"args\":{\"_genkey_0\":\"X-Env\",\"_genkey_1\":\"prod\"}},"
                        + "{\"name\":\"TokenRelay\",\"args\":{}}"
                        + "]",
                5,
                "{\"team\":\"platform\"}",
                true,
                OffsetDateTime.now()
        );

        RouteDefinition rd = mapper.toRouteDefinition(row);

        assertThat(rd.getPredicates()).extracting("name").containsExactly("Path", "Method");
        assertThat(rd.getFilters()).extracting("name").containsExactly("AddRequestHeader", "TokenRelay");
        assertThat(rd.getOrder()).isEqualTo(5);
        assertThat(rd.getMetadata()).containsEntry("team", "platform");
    }

    @Test
    void rejectsMalformedPredicatesJson() {
        GatewayRoute row = new GatewayRoute(
                "bad",
                "https://example.com",
                "not valid json",
                "[]",
                0,
                "{}",
                true,
                OffsetDateTime.now()
        );

        assertThatThrownBy(() -> mapper.toRouteDefinition(row))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed route definition JSON");
    }

    @Test
    void handlesNullJsonFields() {
        GatewayRoute row = new GatewayRoute(
                "null-fields",
                "https://example.com",
                null,
                null,
                0,
                null,
                true,
                OffsetDateTime.now()
        );

        RouteDefinition rd = mapper.toRouteDefinition(row);

        assertThat(rd.getPredicates()).isEmpty();
        assertThat(rd.getFilters()).isEmpty();
        assertThat(rd.getMetadata()).isEmpty();
    }
}
