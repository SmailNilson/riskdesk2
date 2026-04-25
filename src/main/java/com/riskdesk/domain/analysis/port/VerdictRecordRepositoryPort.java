package com.riskdesk.domain.analysis.port;

import com.riskdesk.domain.analysis.model.LiveAnalysisSnapshot;
import com.riskdesk.domain.analysis.model.LiveVerdict;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Timeframe;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Append-only repository of {@link LiveVerdict} records, each carrying the
 * snapshot it was scored from. Used for:
 * <ul>
 *   <li>Replay determinism (re-score historical snapshots with new weights)</li>
 *   <li>Audit trail (why did the system arm this trade at T?)</li>
 *   <li>Backtest harness</li>
 * </ul>
 */
public interface VerdictRecordRepositoryPort {

    long save(LiveAnalysisSnapshot snapshot, LiveVerdict verdict);

    Optional<LiveVerdict> findLatest(Instrument instrument, Timeframe timeframe);

    List<LiveVerdict> findRecent(Instrument instrument, Timeframe timeframe, int limit);

    List<RecordedAnalysis> findBetween(Instrument instrument, Timeframe timeframe,
                                        Instant from, Instant to);

    int deleteByDecisionTimestampBefore(Instant cutoff);

    /** Pair retrieved for backtest replay. */
    record RecordedAnalysis(LiveAnalysisSnapshot snapshot, LiveVerdict verdict) {}
}
