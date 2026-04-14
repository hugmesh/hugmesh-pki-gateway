package com.example.gateway;

import com.example.gateway.support.PostgresInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke test: the Spring application context must start with a real Postgres behind
 * R2DBC. Uses Testcontainers; the class is disabled automatically when Docker isn't
 * available on the host. Uses {@code MOCK} web environment because Spring Cloud
 * Gateway's Netty auto-config requires a {@code ServerProperties} bean, which
 * {@code NONE} doesn't provide. Activates the {@code local} profile so
 * {@code OAuth2ClientConfig#localClientRegistrationRepository} provides the
 * OAuth2 client registration without any external server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ContextConfiguration(initializers = PostgresInitializer.class)
@ActiveProfiles("local")
@Testcontainers(disabledWithoutDocker = true)
class GatewayApplicationTests {

    @Test
    void contextLoads() {
        // Context-load only. If the bean graph is broken this test fails.
    }
}
