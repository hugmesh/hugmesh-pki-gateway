package systems.snapdragon.gateway.routes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Read-only {@link RouteDefinitionLocator} backed by the Postgres {@code gateway_routes}
 * table. Registering this bean (and NOT a {@code RouteDefinitionRepository}) keeps Spring
 * Cloud Gateway's route source strictly read-only: no writer interface is ever exposed.
 */
@Component
public class DbRouteDefinitionLocator implements RouteDefinitionLocator {

    private static final Logger log = LoggerFactory.getLogger(DbRouteDefinitionLocator.class);

    private final GatewayRouteRepository repository;
    private final RouteMapper routeMapper;

    public DbRouteDefinitionLocator(GatewayRouteRepository repository, RouteMapper routeMapper) {
        this.repository = repository;
        this.routeMapper = routeMapper;
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return repository.findAllEnabled()
                .flatMap(row -> {
                    try {
                        return Flux.just(routeMapper.toRouteDefinition(row));
                    } catch (RuntimeException ex) {
                        log.warn("Skipping malformed route row id={}: {}", row.id(), ex.getMessage());
                        return Flux.empty();
                    }
                })
                .retryWhen(Retry.backoff(5, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(10))
                        .doBeforeRetry(sig -> log.warn("Route query failed (attempt {}): {}",
                                sig.totalRetries() + 1, sig.failure().toString())));
    }
}
