package systems.snapdragon.gateway.security;

import systems.snapdragon.gateway.support.PostgresInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the reactive security chain: unauthenticated calls to a proxied path are
 * redirected to the OAuth2 authorization endpoint, proving that the OAuth2 client is
 * wired (via the {@code local} profile's mock registration) and the route source is
 * loading the example route.
 *
 * <p>Automatically disabled when Docker isn't available on the host.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = PostgresInitializer.class)
@ActiveProfiles("local")
@Testcontainers(disabledWithoutDocker = true)
class SecurityConfigTest {

    @Autowired
    WebTestClient webTestClient;

    @Test
    void unauthenticatedActuatorHealthIsPermitted() {
        webTestClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void unauthenticatedRouteRedirectsToOAuth2Authorization() {
        webTestClient.get().uri("/get/anything")
                .exchange()
                .expectStatus().is3xxRedirection()
                .expectHeader().valueMatches("Location", ".*/oauth2/authorization/casdoor.*");
    }
}
