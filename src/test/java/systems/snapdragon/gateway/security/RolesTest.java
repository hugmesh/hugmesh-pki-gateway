package systems.snapdragon.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Roles.fromJwt / has / isAdmin / isGameMaster contract.
 *
 * <p>Mirrors the same test in snapdragon-gateway so the two ports of
 * the Roles utility stay in lockstep — when either side drifts the
 * test signals which one is wrong.
 */
class RolesTest {

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("test")
            .header("alg", "none")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .subject("user-id-123")
            .claims(c -> c.putAll(claims))
            .build();
    }

    @Test
    void rolesAsListOfStrings() {
        assertThat(Roles.fromJwt(jwt(Map.of("roles", List.of("admin", "player")))))
            .containsExactlyInAnyOrder("ADMIN", "PLAYER");
    }

    @Test
    void rolesAsCasdoorObjects_default_shape() {
        Jwt j = jwt(Map.of("roles", List.of(
                Map.of("name", "admin"),
                Map.of("name", "game-master"))));
        assertThat(Roles.fromJwt(j))
            .containsExactlyInAnyOrder("ADMIN", "GAME-MASTER");
    }

    @Test
    void isAdmin_legacyBoolean_or_roleClaim() {
        assertThat(Roles.isAdmin(jwt(Map.of("isAdmin", true)))).isTrue();
        assertThat(Roles.isAdmin(jwt(Map.of("roles", List.of("admin"))))).isTrue();
        assertThat(Roles.isAdmin(jwt(Map.of("roles", List.of("player"))))).isFalse();
    }

    @Test
    void isGameMaster_acceptsBothHyphenAndUnderscoreVariants() {
        assertThat(Roles.isGameMaster(jwt(Map.of("roles", List.of("game-master"))))).isTrue();
        assertThat(Roles.isGameMaster(jwt(Map.of("roles", List.of("game_master"))))).isTrue();
        assertThat(Roles.isGameMaster(jwt(Map.of("roles", List.of("admin"))))).isFalse();
    }
}
