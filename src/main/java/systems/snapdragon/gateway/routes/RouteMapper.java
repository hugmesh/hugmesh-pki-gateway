package systems.snapdragon.gateway.routes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Converts a {@link GatewayRoute} row into Spring Cloud Gateway's {@link RouteDefinition}.
 * JSON columns are decoded via Jackson into name/args pairs.
 */
@Component
public class RouteMapper {

    private static final TypeReference<List<Map<String, Object>>> DEFINITION_LIST =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> METADATA_MAP =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public RouteMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RouteDefinition toRouteDefinition(GatewayRoute row) {
        RouteDefinition rd = new RouteDefinition();
        rd.setId(row.id());
        rd.setUri(URI.create(row.uri()));
        rd.setOrder(row.routeOrder());
        rd.setPredicates(parseDefinitions(row.predicates(), this::toPredicate));
        rd.setFilters(parseDefinitions(row.filters(), this::toFilter));
        rd.setMetadata(parseMetadata(row.metadata()));
        return rd;
    }

    private <T> List<T> parseDefinitions(String json,
                                         BiFunction<String, Map<String, String>, T> builder) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> entries = objectMapper.readValue(json, DEFINITION_LIST);
            List<T> out = new ArrayList<>(entries.size());
            for (Map<String, Object> entry : entries) {
                String name = (String) entry.get("name");
                Map<String, String> args = stringifyArgs(entry.get("args"));
                out.add(builder.apply(name, args));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed route definition JSON: " + json, e);
        }
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, METADATA_MAP);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed route metadata JSON: " + json, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringifyArgs(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("args must be a JSON object, got: " + raw);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue() == null ? null : String.valueOf(e.getValue()));
        }
        return out;
    }

    private PredicateDefinition toPredicate(String name, Map<String, String> args) {
        PredicateDefinition p = new PredicateDefinition();
        p.setName(name);
        p.setArgs(args);
        return p;
    }

    private FilterDefinition toFilter(String name, Map<String, String> args) {
        FilterDefinition f = new FilterDefinition();
        f.setName(name);
        f.setArgs(args);
        return f;
    }
}
