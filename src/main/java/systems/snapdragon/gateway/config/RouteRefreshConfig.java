package systems.snapdragon.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodically publishes {@link RefreshRoutesEvent} so Spring Cloud Gateway's
 * cached route locator re-queries the database. The interval is configured via
 * {@code gateway.routes.refresh-interval} (default 30s).
 */
@Configuration
public class RouteRefreshConfig {

    private static final Logger log = LoggerFactory.getLogger(RouteRefreshConfig.class);

    private final ApplicationEventPublisher publisher;

    public RouteRefreshConfig(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${gateway.routes.refresh-interval:30s}",
               initialDelayString = "${gateway.routes.refresh-interval:30s}")
    public void refreshRoutes() {
        log.debug("Publishing RefreshRoutesEvent");
        publisher.publishEvent(new RefreshRoutesEvent(this));
    }
}
