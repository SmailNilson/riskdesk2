package com.riskdesk.application.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.event.WallEpisodeClosed;
import com.riskdesk.domain.orderflow.model.DepthMetrics;
import com.riskdesk.domain.orderflow.model.WallEpisode;
import com.riskdesk.domain.orderflow.port.MarketDepthPort;
import com.riskdesk.domain.orderflow.service.WallTracker;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wall traceability (UC-OF-012): polls the live L2 ladders and feeds one domain
 * {@link WallTracker} per instrument, so every DOM "WALL" level gets a recorded
 * lifecycle — where it appeared, how big it grew, how long it sat, and how it
 * ended (CONSUMED / PULLED / FADED / OUT_OF_RANGE).
 *
 * <p>Closed episodes are published as {@link WallEpisodeClosed} domain events
 * (persisted by {@link OrderFlowEventPersistenceService}); the live episode set
 * is cached for {@code GET /api/order-flow/walls/{instrument}}.</p>
 */
@Service
public class WallTrackingService {

    private static final Logger log = LoggerFactory.getLogger(WallTrackingService.class);

    private final ObjectProvider<MarketDepthPort> depthPortProvider;
    private final OrderFlowProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    private final Map<Instrument, WallTracker> trackers = new ConcurrentHashMap<>();
    private final Map<Instrument, List<WallEpisode>> activeCache = new ConcurrentHashMap<>();

    public WallTrackingService(ObjectProvider<MarketDepthPort> depthPortProvider,
                               OrderFlowProperties properties,
                               ApplicationEventPublisher eventPublisher) {
        this.depthPortProvider = depthPortProvider;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelayString = "${riskdesk.order-flow.wall-tracker.eval-interval-ms:1000}",
               initialDelay = 30_000)
    public void trackWalls() {
        if (!properties.getWallTracker().isEnabled()) return;
        MarketDepthPort depthPort = depthPortProvider.getIfAvailable();
        if (depthPort == null) return;

        Instant now = Instant.now();
        for (String instrumentName : properties.getDepth().getInstruments()) {
            Instrument instrument;
            try {
                instrument = Instrument.valueOf(instrumentName);
            } catch (IllegalArgumentException e) {
                continue;
            }
            try {
                Optional<DepthMetrics> depth = depthPort.currentDepth(instrument);
                if (depth.isEmpty()) continue;

                WallTracker tracker = trackers.computeIfAbsent(instrument, this::newTracker);
                for (WallEpisode episode : tracker.onSnapshot(depth.get(), now)) {
                    log.info("Wall episode closed: {} {} @{} maxSize={} duration={}s outcome={}",
                             instrument, episode.side(), episode.price(), episode.maxSize(),
                             String.format("%.1f", episode.durationSeconds()), episode.outcome());
                    eventPublisher.publishEvent(new WallEpisodeClosed(instrument, episode, now));
                }
                activeCache.put(instrument, tracker.activeEpisodes(depth.get(), now));
            } catch (Exception e) {
                log.debug("Wall tracking failed for {}: {}", instrument, e.toString());
            }
        }
    }

    /** Live (still-flagged) wall episodes from the last tracking pass. */
    public List<WallEpisode> activeWalls(Instrument instrument) {
        return activeCache.getOrDefault(instrument, List.of());
    }

    private WallTracker newTracker(Instrument instrument) {
        OrderFlowProperties.WallTracker cfg = properties.getWallTracker();
        return new WallTracker(
            instrument,
            instrument.getTickSize().doubleValue(),
            new WallTracker.Config(
                cfg.getGraceSeconds(),
                cfg.getMinLifetimeSeconds(),
                cfg.getConsumedProximityTicks(),
                cfg.getPulledRemnantRatio(),
                cfg.getMinSize()
            )
        );
    }
}
