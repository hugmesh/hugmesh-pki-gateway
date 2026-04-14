package com.example.gateway.health;

import com.example.gateway.routes.GatewayRouteRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component("routes")
public class RoutesHealthIndicator implements ReactiveHealthIndicator {

    private final GatewayRouteRepository repository;

    public RoutesHealthIndicator(GatewayRouteRepository repository) {
        this.repository = repository;
    }

    @Override
    public Mono<Health> health() {
        return repository.findAllEnabled()
                .count()
                .map(count -> {
                    Health.Builder builder = count > 0 ? Health.up() : Health.down();
                    return builder.withDetail("enabledRouteCount", count).build();
                })
                .onErrorResume(ex -> Mono.just(Health.down(ex).build()));
    }
}
