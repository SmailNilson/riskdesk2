package com.riskdesk.application.service.analysis;

import com.riskdesk.domain.analysis.model.DirectionalBias;
import com.riskdesk.domain.analysis.model.LiveAnalysisSnapshot;
import com.riskdesk.domain.analysis.model.LiveVerdict;
import com.riskdesk.domain.analysis.model.ScoringWeights;
import com.riskdesk.domain.analysis.model.TradeScenario;
import com.riskdesk.domain.analysis.port.VerdictRecordRepositoryPort;
import com.riskdesk.domain.analysis.service.ScenarioGenerator;
import com.riskdesk.domain.analysis.service.TriLayerScoringEngine;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Timeframe;
import com.riskdesk.infrastructure.config.LiveAnalysisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level facade: capture snapshot → score → generate scenarios → persist
 * → publish via WebSocket. Used by the {@code LiveAnalysisController} and by
 * the scheduled scanner.
 */
@Service
public class LiveVerdictService {

    private static final Logger log = LoggerFactory.getLogger(LiveVerdictService.class);
    private static final Duration VALIDITY = Duration.ofMinutes(5);

    private final AnalysisSnapshotAggregator aggregator;
    private final TriLayerScoringEngine scoringEngine;
    private final ScenarioGenerator scenarioGenerator;
    private final VerdictRecordRepositoryPort verdictRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final LiveAnalysisProperties analysisProperties;

    public LiveVerdictService(AnalysisSnapshotAggregator aggregator,
                               VerdictRecordRepositoryPort verdictRepository,
                               SimpMessagingTemplate messagingTemplate,
                               LiveAnalysisProperties analysisProperties) {
        this.aggregator = aggregator;
        this.scoringEngine = new TriLayerScoringEngine(ScoringWeights.defaults());
        this.scenarioGenerator = new ScenarioGenerator();
        this.verdictRepository = verdictRepository;
        this.messagingTemplate = messagingTemplate;
        this.analysisProperties = analysisProperties;
    }

    /**
     * Snapshot of which (instrument, timeframe) pairs the scheduler is currently
     * scanning. Surfaced to the dashboard so the panel can short-circuit with a
     * clear "not scanned" message instead of polling /latest forever for tabs
     * that will never produce verdicts (PR #270 review).
     */
    public ScanConfigView getScanConfig() {
        return new ScanConfigView(
            analysisProperties.isSchedulerEnabled(),
            List.copyOf(analysisProperties.getInstruments()),
            List.copyOf(analysisProperties.getTimeframes()),
            analysisProperties.getPollIntervalMs()
        );
    }

    public record ScanConfigView(
        boolean schedulerEnabled,
        List<String> instruments,
        List<String> timeframes,
        long pollIntervalMs
    ) {}

    public LiveVerdict computeAndPublish(Instrument instrument, Timeframe timeframe) {
        LiveAnalysisSnapshot snap = aggregator.capture(instrument, timeframe);
        DirectionalBias bias = scoringEngine.score(snap);
        List<TradeScenario> scenarios = scenarioGenerator.generate(snap, bias);

        LiveVerdict verdict = new LiveVerdict(
            instrument, timeframe, snap.decisionTimestamp(),
            snap.scoringEngineVersion(), snap.currentPrice(),
            bias, scenarios, snap.decisionTimestamp().plus(VALIDITY)
        );

        try {
            long id = verdictRepository.save(snap, verdict);
            log.debug("Verdict persisted id={} {} {} primary={} confidence={}",
                id, instrument, timeframe, bias.primary(), bias.confidence());
        } catch (Exception e) {
            log.warn("Failed to persist verdict for {} {}: {}", instrument, timeframe, e.getMessage());
        }

        publishToWebsocket(verdict);
        return verdict;
    }

    public List<LiveVerdict> findRecent(Instrument instrument, Timeframe timeframe, int limit) {
        return verdictRepository.findRecent(instrument, timeframe, limit);
    }

    public java.util.Optional<LiveVerdict> findLatest(Instrument instrument, Timeframe timeframe) {
        return verdictRepository.findLatest(instrument, timeframe);
    }

    /** Pure score — no persistence, no publish. Used by replay/backtest. */
    public LiveVerdict scoreOnly(LiveAnalysisSnapshot snap, ScoringWeights weights) {
        TriLayerScoringEngine engine = new TriLayerScoringEngine(weights);
        DirectionalBias bias = engine.score(snap);
        List<TradeScenario> scenarios = scenarioGenerator.generate(snap, bias);
        return new LiveVerdict(
            snap.instrument(), snap.timeframe(), snap.decisionTimestamp(),
            snap.scoringEngineVersion(), snap.currentPrice(),
            bias, scenarios, snap.decisionTimestamp().plus(VALIDITY)
        );
    }

    private void publishToWebsocket(LiveVerdict v) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instrument", v.instrument().name());
        payload.put("timeframe", v.timeframe().label());
        payload.put("decisionTimestamp", v.decisionTimestamp().toString());
        payload.put("scoringEngineVersion", v.scoringEngineVersion());
        payload.put("currentPrice", v.currentPrice());
        payload.put("primary", v.bias().primary().name());
        payload.put("confidence", v.bias().confidence());
        payload.put("structureScore", v.bias().structure().value());
        payload.put("orderFlowScore", v.bias().orderFlow().value());
        payload.put("momentumScore", v.bias().momentum().value());
        payload.put("scenarios", v.scenarios());
        payload.put("contradictions", v.bias().contradictions());
        payload.put("standAsideReason", v.bias().standAsideReason());
        payload.put("validUntil", v.validUntil().toString());

        messagingTemplate.convertAndSend(
            "/topic/live-analysis/" + v.instrument().name() + "/" + v.timeframe().label(),
            payload);
    }
}
