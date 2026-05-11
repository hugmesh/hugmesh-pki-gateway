package systems.snapdragon.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Reactive Spring Security configuration for the id-gateway.
 *
 * <p>Wires the gateway in TWO complementary modes — the same JWT issued
 * by Casdoor flows through both paths depending on how the client
 * arrived:
 *
 * <ol>
 *   <li><b>OAuth2 Login client</b> (oauth2Login + oauth2Client) — for
 *       browser sessions. Users land at the gateway, get redirected
 *       to Casdoor via the {@code casdoor} registration in
 *       application.yml, callback lands here with an authenticated
 *       session, and TokenRelay forwards the access token to
 *       downstream services.</li>
 *   <li><b>OAuth2 Resource Server (JWT)</b> — for programmatic
 *       Bearer-token traffic. Same Casdoor issuer; same JWKS
 *       endpoint. The custom authorities converter below maps
 *       Casdoor's {@code roles} / {@code permissions} / {@code isAdmin}
 *       claims to Spring authorities the SAME way the
 *       snapdragon.games BFF and the snapdragon-gateway do, so a
 *       JWT minted by Casdoor is recognised consistently across the
 *       entire platform.</li>
 * </ol>
 *
 * <p>Path policy:
 *
 * <ul>
 *   <li>{@code /actuator/health/**} + {@code /actuator/info} — public
 *       (Kubernetes / monitoring probes).</li>
 *   <li>{@code /actuator/**} — restricted to {@code ROLE_GAME-MASTER}.
 *       Health detail / route enumeration / gateway internals are
 *       infra surfaces; leaking them would tell an attacker which
 *       routes exist + which pods are healthy. Aligns with the
 *       snapdragon-games-gateway policy.</li>
 *   <li>everything else — authenticated.</li>
 * </ul>
 *
 * <p><b>Admin works on the internal token alone.</b> A request
 * carrying a valid Casdoor JWT with {@code ROLE_ADMIN} or
 * {@code ROLE_GAME-MASTER} authenticates successfully and reaches
 * every downstream gated path. No external OAuth provider needed
 * for the admin path.
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/actuator/**").hasRole("GAME-MASTER")
                        .anyExchange().authenticated())
                .oauth2Login(Customizer.withDefaults())
                .oauth2Client(Customizer.withDefaults())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter())))
                .build();
    }

    private ReactiveJwtAuthenticationConverter jwtConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(casdoorAuthoritiesConverter());
        return converter;
    }

    /**
     * Mirrors the snapdragon.games BFF's
     * {@code games.snapdragon.bff.config.SecurityConfig.casdoorAuthoritiesConverter()}
     * exactly. Three claim sources, in precedence:
     *
     * <ol>
     *   <li>{@code roles} array — accepts list-of-strings AND
     *       list-of-objects (Casdoor's default token shape where each
     *       role is {@code {"name": "<role>", ...}}). Each entry
     *       becomes {@code ROLE_<UPPER>}.</li>
     *   <li>{@code permissions} — verb-noun strings, non-prefixed
     *       authorities for fine-grained {@code @PreAuthorize}.</li>
     *   <li>{@code isAdmin} — legacy boolean → {@code ROLE_ADMIN}.</li>
     * </ol>
     *
     * <p>Every authenticated principal also gets {@code ROLE_USER}
     * so {@code .authenticated()} matchers work uniformly.
     */
    private Converter<Jwt, Flux<GrantedAuthority>> casdoorAuthoritiesConverter() {
        return new Converter<>() {
            @Override
            public Flux<GrantedAuthority> convert(Jwt jwt) {
                List<GrantedAuthority> authorities = new ArrayList<>();
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));

                Object rolesClaim = jwt.getClaim("roles");
                if (rolesClaim instanceof Collection<?> list) {
                    for (Object r : list) {
                        String name = roleNameOf(r);
                        if (!name.isBlank()) {
                            authorities.add(new SimpleGrantedAuthority(
                                    "ROLE_" + name.trim().toUpperCase()));
                        }
                    }
                }

                Object permsClaim = jwt.getClaim("permissions");
                if (permsClaim instanceof Collection<?> list) {
                    for (Object p : list) {
                        if (p != null) {
                            authorities.add(new SimpleGrantedAuthority(p.toString().trim()));
                        }
                    }
                }

                if (Boolean.TRUE.equals(jwt.getClaim("isAdmin"))) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                }

                return Flux.fromIterable(authorities);
            }
        };
    }

    private static String roleNameOf(Object item) {
        if (item instanceof String s) return s;
        if (item instanceof Map<?, ?> m) {
            Object name = m.get("name");
            if (name instanceof String s && !s.isBlank()) return s;
        }
        return "";
    }
}
