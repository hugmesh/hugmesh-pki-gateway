package systems.snapdragon.gateway.routes;

import systems.snapdragon.gateway.support.PostgresInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: spins up a real Postgres via Testcontainers, applies the Liquibase
 * changelog (which creates the schema AND seeds the example route), wires the gateway's
 * R2DBC properties to the container, and verifies that {@link DbRouteDefinitionLocator}
 * returns the seeded route with correctly parsed predicates and filters.
 *
 * <p>Automatically disabled when Docker isn't available on the host.
 * Uses MOCK web env + {@code local} profile so Spring Cloud Gateway's Netty
 * config wires and the OAuth2 client registration comes from the local mock bean.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ContextConfiguration(initializers = PostgresInitializer.class)
@ActiveProfiles("local")
@Testcontainers(disabledWithoutDocker = true)
class DbRouteDefinitionLocatorIT {

    @Autowired
    DbRouteDefinitionLocator locator;

    @Test
    void loadsSeedRoutesFromPostgres() {
        List<RouteDefinition> routes = locator.getRouteDefinitions().collectList().block();

        assertThat(routes).isNotNull();
        assertThat(routes).extracting(RouteDefinition::getId).contains("example-route");

        RouteDefinition example = routes.stream()
                .filter(r -> r.getId().equals("example-route"))
                .findFirst()
                .orElseThrow();

        assertThat(example.getUri()).hasToString("https://httpbin.org");
        assertThat(example.getPredicates()).hasSize(1);
        assertThat(example.getPredicates().get(0).getName()).isEqualTo("Path");
        assertThat(example.getPredicates().get(0).getArgs())
                .containsEntry("_genkey_0", "/get/**");
        assertThat(example.getFilters()).hasSize(1);
        assertThat(example.getFilters().get(0).getName()).isEqualTo("TokenRelay");
    }

    @Test
    void emitsAsFlux() {
        StepVerifier.create(locator.getRouteDefinitions())
                .expectNextCount(1)
                .verifyComplete();
    }
}
