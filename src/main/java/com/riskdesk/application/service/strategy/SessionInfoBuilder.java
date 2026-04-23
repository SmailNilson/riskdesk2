package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.engine.strategy.model.SessionInfo;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.SessionPhase;
import com.riskdesk.domain.shared.TradingSessionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Builds a {@link SessionInfo} from the pure-domain
 * {@link TradingSessionResolver}. Clock-injected so tests can fix a specific
 * instant (kill zone / maintenance scenarios).
 *
 * <p>Failure behaviour mirrors {@link PortfolioStateBuilder}: on any exception
 * we return {@link SessionInfo#unknown()} and the {@code SessionTimingAgent}
 * abstains. Given session resolution is pure-domain and thus deterministic,
 * failures here would indicate a library bug rather than a transient outage.
 */
@Component
public class SessionInfoBuilder {

    private static final Logger log = LoggerFactory.getLogger(SessionInfoBuilder.class);

    private final Clock clock;

    public SessionInfoBuilder(Clock clock) {
        this.clock = clock;
    }

    public SessionInfo build(Instrument instrument) {
        try {
            Instant now = clock.instant();
            SessionPhase phase = TradingSessionResolver.currentPhase(now);
            boolean killZone = TradingSessionResolver.isWithinKillZone(now);
            boolean marketOpen = TradingSessionResolver.isMarketOpen(now, instrument);
            boolean maintenance = TradingSessionResolver.isStandardMaintenanceWindow(now);
            return new SessionInfo(
                phase != null ? phase.name() : null,
                killZone,
                marketOpen,
                maintenance
            );
        } catch (Exception e) {
            log.debug("SessionInfoBuilder failed for {}: {}", instrument, e.getMessage());
            return SessionInfo.unknown();
        }
    }
}
