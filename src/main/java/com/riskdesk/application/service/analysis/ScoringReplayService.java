package com.riskdesk.application.service.analysis;

import com.riskdesk.domain.analysis.model.Direction;
import com.riskdesk.domain.analysis.model.LiveVerdict;
import com.riskdesk.domain.analysis.model.ScoringWeights;
import com.riskdesk.domain.analysis.port.VerdictRecordRepositoryPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Timeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-scores historical snapshots with candidate weights so the user can A/B
 * different formulae against real recorded data. No look-ahead is possible —
 * the snapshots have their own {@code decisionTimestamp} and the engine only
 * reads from them.
 */
@Service
public class ScoringReplayService {

    private static final Logger log = LoggerFactory.getLogger(ScoringReplayService.class);

    private final VerdictRecordRepositoryPort verdictRepository;
    private final LiveVerdictService liveVerdictService;

    public ScoringReplayService(VerdictRecordRepositoryPort verdictRepository,
                                 LiveVerdictService liveVerdictService) {
        this.verdictRepository = verdictRepository;
        this.liveVerdictService = liveVerdictService;
    }

    public ReplayReport replay(Instrument instrument, Timeframe timeframe,
                                 Instant from, Instant to, ScoringWeights weights) {
        var recorded = verdictRepository.findBetween(instrument, timeframe, from, to);
        log.info("Replay {} snapshots for {} {} {} → {}",
            recorded.size(), instrument, timeframe, from, to);

        Map<String, Integer> directionDistribution = new HashMap<>();
        List<ReplaySample> samples = new ArrayList<>(recorded.size());
        int agreements = 0;
        int total = 0;

        for (var rec : recorded) {
            LiveVerdict replayed = liveVerdictService.scoreOnly(rec.snapshot(), weights);
            String direction = replayed.bias().primary().name();
            directionDistribution.merge(direction, 1, Integer::sum);

            // Agreement check — how often does the new weights set match the original?
            if (replayed.bias().primary() == rec.verdict().bias().primary()) agreements++;
            total++;

            samples.add(new ReplaySample(
                rec.snapshot().decisionTimestamp(),
                rec.verdict().bias().primary().name(), rec.verdict().bias().confidence(),
                replayed.bias().primary().name(), replayed.bias().confidence(),
                replayed.bias().structure().value(),
                replayed.bias().orderFlow().value(),
                replayed.bias().momentum().value()
            ));
        }

        double agreementRatio = total == 0 ? 0.0 : (double) agreements / total;
        long actionable = samples.stream()
            .filter(s -> !"NEUTRAL".equals(s.replayedDirection())).count();

        return new ReplayReport(
            instrument.name(), timeframe.label(), from, to,
            weights, total, agreements, agreementRatio, actionable,
            directionDistribution, samples);
    }

    public record ReplaySample(
        Instant decisionTimestamp,
        String originalDirection, int originalConfidence,
        String replayedDirection, int replayedConfidence,
        double structureScore, double orderFlowScore, double momentumScore
    ) {}

    public record ReplayReport(
        String instrument,
        String timeframe,
        Instant from,
        Instant to,
        ScoringWeights weights,
        int totalSnapshots,
        int agreementCount,
        double agreementRatio,
        long actionableCount,
        Map<String, Integer> directionDistribution,
        List<ReplaySample> samples
    ) {}
}
