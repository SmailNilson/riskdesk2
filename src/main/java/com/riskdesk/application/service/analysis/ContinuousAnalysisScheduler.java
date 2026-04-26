package com.riskdesk.application.service.analysis;

import com.riskdesk.domain.analysis.port.AnalysisConfigPort;
import com.riskdesk.domain.analysis.port.VerdictRecordRepositoryPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Timeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Drives the live analysis pipeline on a fixed interval so {@code live_verdict_records}
 * fills up automatically. Without this, the table only grows on user-initiated REST hits
 * and the replay/backtest service has nothing to chew on.
 * <p>
 * The scheduler skips silently when:
 * <ul>
 *   <li>The feature flag is disabled</li>
 *   <li>The aggregator throws {@code StaleSnapshotException} (data is stale or unavailable)</li>
 *   <li>Any other transient error — logged at WARN, never propagated</li>
 * </ul>
 */
@Service
public class ContinuousAnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(ContinuousAnalysisScheduler.class);

    private final LiveVerdictService verdictService;
    private final VerdictRecordRepositoryPort verdictRepository;
    private final AnalysisConfigPort config;

    public ContinuousAnalysisScheduler(LiveVerdictService verdictService,
                                         VerdictRecordRepositoryPort verdictRepository,
                                         AnalysisConfigPort config) {
        this.verdictService = verdictService;
        this.verdictRepository = verdictRepository;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${riskdesk.analysis.poll-interval-ms:15000}",
                initialDelayString = "${riskdesk.analysis.initial-delay-ms:60000}")
    public void scanAll() {
        if (!config.isSchedulerEnabled()) return;

        for (String instrumentName : config.getInstruments()) {
            Instrument instrument;
            try {
                instrument = Instrument.valueOf(instrumentName);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown instrument in analysis config: {}", instrumentName);
                continue;
            }
            for (String tfLabel : config.getTimeframes()) {
                Timeframe tf;
                try {
                    tf = Timeframe.fromLabel(tfLabel);
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown timeframe in analysis config: {}", tfLabel);
                    continue;
                }
                tryCompute(instrument, tf);
            }
        }
    }

    private void tryCompute(Instrument instrument, Timeframe timeframe) {
        try {
            verdictService.computeAndPublish(instrument, timeframe);
        } catch (StaleSnapshotException e) {
            // Common during low-data periods (warm-up, weekends, contract roll) — debug only
            log.debug("Skipping {} {} — stale snapshot: {}", instrument, timeframe, e.getMessage());
        } catch (Exception e) {
            log.warn("Live analysis scan failed for {} {}: {}", instrument, timeframe, e.getMessage());
        }
    }

    /**
     * Daily purge of verdict history — prevents unbounded growth of the snapshot
     * JSON column. Gated by the same {@code schedulerEnabled} flag as {@link #scanAll}:
     * disabling the feature for troubleshooting / data freeze must NOT silently
     * delete historical verdicts the next morning. Operators who want to keep
     * deleting old data while pausing scanning should use a future dedicated
     * {@code purgeEnabled} flag (not yet introduced — PR #270 review).
     */
    @Scheduled(cron = "0 17 3 * * *")
    public void purgeStaleVerdicts() {
        if (!config.isSchedulerEnabled()) {
            log.debug("Verdict purge skipped — scheduler is disabled");
            return;
        }
        Instant cutoff = Instant.now().minus(config.getRetentionDays(), ChronoUnit.DAYS);
        try {
            int deleted = verdictRepository.deleteByDecisionTimestampBefore(cutoff);
            if (deleted > 0) {
                log.info("Purged {} verdict records older than {} ({} days)",
                    deleted, cutoff, config.getRetentionDays());
            }
        } catch (Exception e) {
            log.warn("Verdict purge failed: {}", e.getMessage());
        }
    }
}
