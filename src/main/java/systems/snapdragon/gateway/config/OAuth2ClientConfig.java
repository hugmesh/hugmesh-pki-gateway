package systems.snapdragon.gateway.config;

import no.nav.security.mock.oauth2.MockOAuth2Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

/**
 * OAuth2 client wiring — TESTS ONLY.
 *
 * <p>The production Casdoor registration lives entirely in
 * {@code application.yml} under {@code spring.security.oauth2.client.*}
 * so each deployment's identity (issuer URI, client id, scopes) is
 * configuration-driven rather than baked into Java {@code @Bean}
 * methods. Spring Boot's OAuth2 client auto-configuration reads those
 * properties and produces a {@link ReactiveClientRegistrationRepository}
 * automatically — same configuration channel snapdragon.games and the
 * snapdragon-games-gateway use.
 *
 * <p>This class keeps ONE bean for the {@code local} test profile:
 * an in-process {@link MockOAuth2Server} so unit + integration tests
 * can complete the Authorization Code flow without an external
 * Casdoor. When the {@code local} profile is active the bean below
 * overrides any yaml-derived registration.
 */
@Configuration
public class OAuth2ClientConfig {

    private static final Logger log = LoggerFactory.getLogger(OAuth2ClientConfig.class);

    private static final String REGISTRATION_ID = "casdoor";
    private static final String LOCAL_ISSUER_ID = "default";

    /**
     * Boots an in-process mock OIDC server on a random port when the
     * {@code local} profile is active. Shut down automatically on
     * context close via {@code destroyMethod = "shutdown"}.
     */
    @Bean(destroyMethod = "shutdown")
    @Profile("local")
    public MockOAuth2Server mockOAuth2Server() throws Exception {
        MockOAuth2Server server = new MockOAuth2Server();
        server.start();
        log.info("Started in-process MockOAuth2Server for local profile at {}",
                server.baseUrl());
        return server;
    }

    @Bean
    @Profile("local")
    public ReactiveClientRegistrationRepository localClientRegistrationRepository(
            MockOAuth2Server mockServer) {

        String issuerUri = mockServer.issuerUrl(LOCAL_ISSUER_ID).toString();
        String wellKnown = mockServer.wellKnownUrl(LOCAL_ISSUER_ID).toString();
        log.info("OAuth2 profile=local — registering client '{}' against mock OIDC at {}",
                REGISTRATION_ID, wellKnown);

        ClientRegistration mock = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId("local-mock-client")
                .clientSecret("local-mock-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .authorizationUri(issuerUri + "/authorize")
                .tokenUri(issuerUri + "/token")
                .userInfoUri(issuerUri + "/userinfo")
                .jwkSetUri(issuerUri + "/jwks")
                .issuerUri(issuerUri)
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .clientName("Local Mock OAuth2")
                .build();

        return new InMemoryReactiveClientRegistrationRepository(mock);
    }
}
