package systems.snapdragon.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Reactive Spring Security configuration.
 *
 * <p>Wires the gateway as an OAuth2 Login client. When the user's
 * {@code spring.security.oauth2.client.*} properties point at Casdoor's issuer
 * URI, Spring Security resolves the issuer's OpenID metadata lazily on the
 * first login — so the app starts even if Casdoor is briefly unreachable.
 *
 * <p>TokenRelay: having {@code spring-boot-starter-oauth2-client} on the
 * classpath auto-registers Spring Cloud Gateway's {@code TokenRelay}
 * filter factory, so seeded routes referencing it "just work".
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .anyExchange().authenticated())
                .oauth2Login(Customizer.withDefaults())
                .oauth2Client(Customizer.withDefaults())
                .build();
    }
}
