package systems.snapdragon.gateway.config;

import no.nav.security.mock.oauth2.MockOAuth2Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

/**
 * OAuth2 client wiring. Exactly one of the two profile-gated repositories
 * below must be active, otherwise Spring Security's {@code oauth2Login()} has
 * no {@link ReactiveClientRegistrationRepository} and the context fails to
 * start.
 *
 * <ul>
 *   <li><b>local</b>: starts an in-process {@link MockOAuth2Server} (the
 *       navikt mock OIDC server, running on a random port) and registers a
 *       client pointing at it. Browser login flows actually complete against
 *       the mock — useful for iterating without any external dependency.</li>
 *   <li><b>casdoor</b>: reads {@code CASDOOR_ISSUER_URI}, {@code CASDOOR_CLIENT_ID},
 *       {@code CASDOOR_CLIENT_SECRET} from the environment and performs OIDC
 *       discovery against the real Casdoor instance. Startup fails fast if
 *       Casdoor is unreachable, which is the intended behavior — we want
 *       deployment-time validation that Casdoor is wired correctly.</li>
 * </ul>
 */
@Configuration
public class OAuth2ClientConfig {

    private static final Logger log = LoggerFactory.getLogger(OAuth2ClientConfig.class);

    private static final String REGISTRATION_ID = "casdoor";
    private static final String LOCAL_ISSUER_ID = "default";

    /**
     * Boots an in-process mock OIDC server on a random port when the
     * {@code local} profile is active. Shut down automatically on context
     * close via {@code destroyMethod = "shutdown"}.
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

    @Bean
    @Profile("casdoor")
    public ReactiveClientRegistrationRepository casdoorClientRegistrationRepository(
            @Value("${CASDOOR_ISSUER_URI}") String issuerUri,
            @Value("${CASDOOR_CLIENT_ID}") String clientId,
            @Value("${CASDOOR_CLIENT_SECRET}") String clientSecret,
            @Value("${CASDOOR_SCOPES:openid,profile,email}") String[] scopes) {

        log.info("OAuth2 profile=casdoor — fetching OIDC discovery from {}", issuerUri);

        ClientRegistration casdoor = ClientRegistrations.fromIssuerLocation(issuerUri)
                .registrationId(REGISTRATION_ID)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope(scopes)
                .clientName("Casdoor")
                .build();

        return new InMemoryReactiveClientRegistrationRepository(casdoor);
    }
}
