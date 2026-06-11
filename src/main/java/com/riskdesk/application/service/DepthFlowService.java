package com.riskdesk.application.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.DepthFlowMetrics;
import com.riskdesk.domain.orderflow.model.DepthMetrics;
import com.riskdesk.domain.orderflow.port.MarketDepthPort;
import com.riskdesk.domain.orderflow.service.DepthFlowAnalyzer;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Depth-flow signals: polls the live L2 snapshots every eval tick and feeds one pure
 * domain {@link DepthFlowAnalyzer} per instrument (same pattern as
 * {@link WallTrackingService}). The resulting {@link DepthFlowMetrics} — OFI
 * (Cont-Kukanov-Stoikov), queue imbalance + micro-price, liquidity vacuum and
 * pull/stack net flow — is cached for {@code GET /api/order-flow/depth-flow/{instrument}}
 * and published as a flat JSON payload on {@code /topic/depth-flow}.
 *
 * <p><b>Stale-feed guard:</b> a snapshot whose own timestamp is older than the
 * configured stale gap means the book is frozen — the analyzer is reset and nothing
 * is emitted. Flow must never be computed across a feed gap (the deltas would mix a
 * pre-freeze book with a post-recovery one and fabricate massive pulls/OFI spikes).</p>
 */
@Service
public class DepthFlowService {

    private static final Logger log = LoggerFactory.getLogger(DepthFlowService.class);

    private final ObjectProvider<MarketDepthPort> depthPortProvider;
    private final OrderFlowProperties properties;
    private final SimpMessagingTemplate messagingTemplate;

    private final Map<Instrument, DepthFlowAnalyzer> analyzers = new ConcurrentHashMap<>();
    private final Map<Instrument, DepthFlowMetrics> latestCache = new ConcurrentHashMap<>();

    public DepthFlowService(ObjectProvider<MarketDepthPort> depthPortProvider,
                            OrderFlowProperties properties,
                            SimpMessagingTemplate messagingTemplate) {
        this.depthPortProvider = depthPortProvider;
        this.properties = properties;
        this.messagingTemplate = messagingTemplate;
    }

    @Scheduled(fixedDelayString = "${riskdesk.order-flow.depth-flow.eval-interval-ms:500}",
               initialDelay = 30_000)
    public void evaluate() {
        if (!properties.getDepthFlow().isEnabled()) return;
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
                DepthMetrics snapshot = depth.get();

                // Frozen book: never compute flow across a feed gap. Reset and emit nothing —
                // the analyzer also self-resets on eval gaps, but a stale snapshot would
                // otherwise read as a legitimate "no flow" stream.
                if (isStale(snapshot, now)) {
                    DepthFlowAnalyzer existing = analyzers.get(instrument);
                    if (existing != null) existing.reset();
                    latestCache.remove(instrument);
                    continue;
                }

                DepthFlowAnalyzer analyzer = analyzers.computeIfAbsent(instrument, this::newAnalyzer);
                analyzer.onSnapshot(snapshot, now).ifPresent(metrics -> {
                    latestCache.put(instrument, metrics);
                    messagingTemplate.convertAndSend("/topic/depth-flow", payload(metrics));
                });
            } catch (Exception e) {
                log.debug("Depth flow evaluation failed for {}: {}", instrument, e.toString());
            }
        }
    }

    /** Latest computed metrics from the last evaluation pass, if the feed is live. */
    public Optional<DepthFlowMetrics> latest(Instrument instrument) {
        return Optional.ofNullable(latestCache.get(instrument));
    }

    private boolean isStale(DepthMetrics snapshot, Instant now) {
        if (snapshot.timestamp() == null) return true; // unknowable age — treat as gap
        double ageSeconds = Duration.between(snapshot.timestamp(), now).toMillis() / 1000.0;
        return ageSeconds > properties.getDepthFlow().getStaleGapSeconds();
    }

    private DepthFlowAnalyzer newAnalyzer(Instrument instrument) {
        OrderFlowProperties.DepthFlow cfg = properties.getDepthFlow();
        return new DepthFlowAnalyzer(
            instrument,
            instrument.getTickSize().doubleValue(),
            new DepthFlowAnalyzer.Config(
                cfg.getStaleGapSeconds(),
                cfg.getMinQueueMass(),
                cfg.getImbalanceEmaSeconds(),
                cfg.getNoiseFloorContracts(),
                cfg.getVacuumDepletionRatio(),
                cfg.getVacuumHoldRatio(),
                cfg.getThinRatio(),
                cfg.getVacuumPersistenceSeconds(),
                cfg.getOfiZFlagThreshold(),
                cfg.getBaselineWindowSeconds()
            )
        );
    }

    /** Flat JSON payload — shared shape for /topic/depth-flow and the REST endpoint. */
    public static Map<String, Object> payload(DepthFlowMetrics m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instrument", m.instrument().name());
        out.put("ofi1s", m.ofi1s());
        out.put("ofi10s", m.ofi10s());
        out.put("ofiEma60s", m.ofiEma60s());
        out.put("ofiZ10s", m.ofiZ10s());
        out.put("ofiExtreme", m.ofiExtreme());
        out.put("queueImbalance", m.queueImbalance());
        out.put("queueImbalanceValid", m.queueImbalanceValid());
        out.put("microPriceOffsetTicks", m.microPriceOffsetTicks());
        out.put("vacuumState", m.vacuumState().name());
        out.put("bidDepthRatio", m.bidDepthRatio());
        out.put("askDepthRatio", m.askDepthRatio());
        out.put("bidPulled10s", m.bidPulled10s());
        out.put("bidStacked10s", m.bidStacked10s());
        out.put("askPulled10s", m.askPulled10s());
        out.put("askStacked10s", m.askStacked10s());
        out.put("pullStackScore", m.pullStackScore());
        out.put("timestamp", m.timestamp() != null ? m.timestamp().toString() : null);
        return out;
    }
}
