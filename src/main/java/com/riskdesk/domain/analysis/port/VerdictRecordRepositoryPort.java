package com.riskdesk.domain.analysis.port;

import com.riskdesk.domain.analysis.model.LiveAnalysisSnapshot;
import com.riskdesk.domain.analysis.model.LiveVerdict;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Timeframe;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

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

    /**
     * @deprecated Use {@link #streamBetween} for replay. Returning the full
     *             range as a list materialises every snapshot+verdict JSON
     *             into the heap at once, which OOMs on multi-day windows
     *             (PR #269 round-8 review).
     */
    @Deprecated
    List<RecordedAnalysis> findBetween(Instrument instrument, Timeframe timeframe,
                                        Instant from, Instant to);

    /**
     * Stream rows in {@code [from, to]} to {@code consumer} in
     * decision-timestamp ascending order. The adapter pages through the
     * underlying table so only one page is held in memory at a time —
     * supports arbitrarily large windows without blowing the heap.
     *
     * @param pageSize  number of rows fetched per DB round-trip; must be &gt; 0
     */
    void streamBetween(Instrument instrument, Timeframe timeframe,
                        Instant from, Instant to,
                        int pageSize, Consumer<RecordedAnalysis> consumer);

    int deleteByDecisionTimestampBefore(Instant cutoff);

    /** Pair retrieved for backtest replay. */
    record RecordedAnalysis(LiveAnalysisSnapshot snapshot, LiveVerdict verdict) {}
}
