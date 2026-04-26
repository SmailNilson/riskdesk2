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

    /**
     * Maximum range a single replay request may scan. Beyond this we reject
     * with {@link IllegalArgumentException} (mapped to HTTP 400). With the
     * scheduler at 15s × 4 instruments × 4 timeframes ≈ 80 rows/min, a 90-day
     * window is roughly 10M rows — already well past sane RAM, but the cap
     * gives operators a clear error rather than a silent OOM.
     */
    public static final java.time.Duration MAX_WINDOW = java.time.Duration.ofDays(90);

    /**
     * Maximum {@code ReplaySample} rows returned in the response payload.
     * Aggregate stats (agreementRatio, distribution, totalSnapshots) still
     * cover the full set; only the per-row array is truncated, keeping the
     * payload bounded for the dashboard / curl. Newest first — operators
     * usually inspect recent behaviour, not the long tail.
     */
    public static final int MAX_SAMPLES_RETURNED = 5_000;

    private final VerdictRecordRepositoryPort verdictRepository;
    private final LiveVerdictService liveVerdictService;

    public ScoringReplayService(VerdictRecordRepositoryPort verdictRepository,
                                 LiveVerdictService liveVerdictService) {
        this.verdictRepository = verdictRepository;
        this.liveVerdictService = liveVerdictService;
    }

    public ReplayReport replay(Instrument instrument, Timeframe timeframe,
                                 Instant from, Instant to, ScoringWeights weights) {
        // PR #269 round-7 review fix: bound the request before any DB work to
        // avoid pulling weeks/months of snapshots into memory and OOM-ing.
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be strictly before to");
        }
        java.time.Duration window = java.time.Duration.between(from, to);
        if (window.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException(
                "Replay window " + window.toDays() + "d exceeds max " + MAX_WINDOW.toDays() + "d. "
                + "Narrow the [from, to] range or run multiple replays.");
        }

        var recorded = verdictRepository.findBetween(instrument, timeframe, from, to);
        log.info("Replay {} snapshots for {} {} {} → {}",
            recorded.size(), instrument, timeframe, from, to);

        Map<String, Integer> directionDistribution = new HashMap<>();
        // Bounded buffer — once we have MAX_SAMPLES_RETURNED, we stop collecting
        // per-row entries but keep computing aggregates over the full set.
        List<ReplaySample> samples = new ArrayList<>(Math.min(recorded.size(), MAX_SAMPLES_RETURNED));
        int agreements = 0;
        int total = 0;
        long actionable = 0L;
        boolean truncated = false;

        for (var rec : recorded) {
            LiveVerdict replayed = liveVerdictService.scoreOnly(rec.snapshot(), weights);
            String direction = replayed.bias().primary().name();
            directionDistribution.merge(direction, 1, Integer::sum);

            if (replayed.bias().primary() == rec.verdict().bias().primary()) agreements++;
            if (!"NEUTRAL".equals(direction)) actionable++;
            total++;

            // Per-row capture only while under the cap; aggregates stay accurate.
            if (samples.size() < MAX_SAMPLES_RETURNED) {
                samples.add(new ReplaySample(
                    rec.snapshot().decisionTimestamp(),
                    rec.verdict().bias().primary().name(), rec.verdict().bias().confidence(),
                    replayed.bias().primary().name(), replayed.bias().confidence(),
                    replayed.bias().structure().value(),
                    replayed.bias().orderFlow().value(),
                    replayed.bias().momentum().value()
                ));
            } else {
                truncated = true;
            }
        }

        double agreementRatio = total == 0 ? 0.0 : (double) agreements / total;
        if (truncated) {
            log.info("Replay sample list truncated from {} to {} for {} {} (aggregates remain accurate)",
                total, MAX_SAMPLES_RETURNED, instrument, timeframe);
        }

        return new ReplayReport(
            instrument.name(), timeframe.label(), from, to,
            weights, total, agreements, agreementRatio, actionable,
            directionDistribution, samples, truncated, MAX_SAMPLES_RETURNED);
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
        List<ReplaySample> samples,
        /** True when totalSnapshots > sampleCap and the {@code samples} list was capped. */
        boolean samplesTruncated,
        /** Cap applied to {@code samples}; aggregates are computed over all rows. */
        int sampleCap
    ) {}
}
