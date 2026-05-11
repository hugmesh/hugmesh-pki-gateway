package systems.snapdragon.gateway.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * JWT role extraction utility — aligned with the snapdragon.games BFF's
 * {@code games.snapdragon.bff.security.Roles}.
 *
 * <p>Casdoor's token template can emit the {@code roles} claim in two
 * shapes:
 *
 * <ol>
 *   <li>List of strings:  {@code "roles": ["admin", "player"]}</li>
 *   <li>List of role objects (default):
 *       {@code "roles": [{"name": "admin", ...}, {"name": "player", ...}]}</li>
 * </ol>
 *
 * <p>Both shapes are handled. Empty / blank entries are skipped silently
 * so a partially-populated role object doesn't introduce a phantom
 * {@code ROLE_} authority.
 *
 * <p>Static helpers below answer the common "does this principal have
 * role X" question from controller code without copying the claim-walk.
 */
public final class Roles {

    private Roles() {}

    /**
     * Returns every role name carried by the JWT, upper-cased and
     * trimmed. Empty list if the claim is missing.
     */
    public static List<String> fromJwt(Jwt jwt) {
        List<String> out = new ArrayList<>();
        if (jwt == null) return out;
        Object claim = jwt.getClaim("roles");
        if (claim instanceof Collection<?> list) {
            for (Object item : list) {
                String name = roleNameOf(item);
                if (!name.isBlank()) out.add(name.toUpperCase());
            }
        }
        return out;
    }

    /** True iff {@code roleName} (case-insensitive) is present in the
     *  JWT's roles claim. */
    public static boolean has(Jwt jwt, String roleName) {
        if (jwt == null || roleName == null) return false;
        String want = roleName.trim().toUpperCase();
        for (String r : fromJwt(jwt)) if (r.equals(want)) return true;
        return false;
    }

    public static boolean isAdmin(Jwt jwt) {
        if (jwt == null) return false;
        if (Boolean.TRUE.equals(jwt.getClaim("isAdmin"))) return true;
        return has(jwt, "ADMIN");
    }

    /** True iff the principal carries the {@code game-master} role
     *  (snapdragon.systems platform admin). Gates infra/health URLs. */
    public static boolean isGameMaster(Jwt jwt) {
        return has(jwt, "GAME-MASTER") || has(jwt, "GAME_MASTER");
    }

    private static String roleNameOf(Object item) {
        if (item == null) return "";
        if (item instanceof String s) return s.trim();
        if (item instanceof Map<?, ?> m) {
            Object name = m.get("name");
            if (name instanceof String s && !s.isBlank()) return s.trim();
        }
        return "";
    }

    /** Casdoor user id (subject) accessor — the single place we choose
     *  between {@code sub} and {@code id} so a Casdoor schema flip
     *  doesn't ripple through controller code. */
    public static String casdoorIdOf(Jwt jwt) {
        if (jwt == null) return null;
        String sub = jwt.getSubject();
        if (sub != null && !sub.isBlank()) return sub;
        Object id = jwt.getClaim("id");
        return id == null ? null : id.toString();
    }

    /** Lowercase username from the {@code name} claim — used by the
     *  revocation list to key on user-friendly identifiers when the
     *  Casdoor id rotates. */
    public static String usernameOf(Jwt jwt) {
        if (jwt == null) return null;
        Object n = jwt.getClaim("name");
        return n == null ? null : n.toString();
    }

    /** Convert {@code List<GrantedAuthority>} → role-name list (strips
     *  the {@code ROLE_} prefix). Useful for log lines / debug. */
    public static List<String> namesOf(Collection<? extends GrantedAuthority> authorities) {
        List<String> out = new ArrayList<>();
        if (authorities == null) return out;
        for (GrantedAuthority a : authorities) {
            String s = a.getAuthority();
            if (s == null) continue;
            out.add(s.startsWith("ROLE_") ? s.substring(5) : s);
        }
        return out;
    }
}
