package com.riskdesk.application.health;

import com.riskdesk.application.service.MarketDataService;
import com.riskdesk.domain.shared.TradingSessionResolver;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Surfaces live-feed freshness on {@code /actuator/health} (component {@code marketDataFeed}) and,
 * via the {@code details} map, the age of the last genuine IBKR tick.
 *
 * <p><b>Always reports {@link Health#up()}</b> — even when the feed is stale — on purpose: a DOWN
 * component flips the aggregate health to 503, which would fail the deployment health-check gate
 * (the boot/deploy probe waits on {@code /actuator/health}). The staleness signal is carried in the
 * details ({@code stale=true}, {@code lastLiveTickAgeSeconds}) for dashboards/alerts to read, while
 * the actual recovery action is owned by {@code MarketDataService.priceFeedFreshnessWatchdog} +
 * {@code IbGatewayNativeClient.forceReconnect}. The watchdog logs at ERROR when it acts, so log-based
 * alerting still fires; this indicator is purely informational.</p>
 */
@Component("marketDataFeed")
@Profile("!test")
public class MarketDataFeedHealthIndicator implements HealthIndicator {

    /** A live tick older than this (during market hours) is reported as stale in the details. */
    private static final long STALE_DETAIL_SECONDS = 120L;

    private final MarketDataService marketDataService;

    public MarketDataFeedHealthIndicator(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @Override
    public Health health() {
        Duration age = marketDataService.liveTickAge();
        boolean marketOpen = TradingSessionResolver.isMarketOpen(Instant.now());
        boolean stale = marketOpen && (age == null || age.getSeconds() > STALE_DETAIL_SECONDS);

        return Health.up()
            .withDetail("lastLiveTickAgeSeconds", age == null ? -1L : age.getSeconds())
            .withDetail("marketOpen", marketOpen)
            .withDetail("databaseFallbackActive", marketDataService.isDatabaseFallbackActive())
            .withDetail("stale", stale)
            .build();
    }
}
