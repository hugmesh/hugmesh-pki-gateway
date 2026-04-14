package systems.snapdragon.gateway.routes;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface GatewayRouteRepository extends ReactiveCrudRepository<GatewayRoute, String> {

    @Query("SELECT * FROM gateway_routes WHERE enabled = true ORDER BY route_order")
    Flux<GatewayRoute> findAllEnabled();
}
