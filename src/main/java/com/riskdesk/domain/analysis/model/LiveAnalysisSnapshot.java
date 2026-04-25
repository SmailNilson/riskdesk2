package com.riskdesk.domain.analysis.model;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Timeframe;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, time-stamped snapshot of all inputs needed by the tri-layer scoring engine.
 * <p>
 * Two distinct timestamps:
 * <ul>
 *   <li>{@code decisionTimestamp} — logical "as-of" instant the snapshot represents.
 *       Order-flow events are filtered to {@code event.timestamp <= decisionTimestamp}
 *       so backtests cannot leak look-ahead data.</li>
 *   <li>{@code captureTimestamp} — wall-clock instant when the snapshot was assembled.
 *       Used only for staleness checks and observability.</li>
 * </ul>
 * The canonical constructor enforces {@code captureTimestamp >= decisionTimestamp}
 * (causality).
 * <p>
 * The {@code scoringEngineVersion} is bumped whenever the scoring rules change
 * in a way that breaks replay determinism. Persisted verdict records carry the
 * version they were scored under.
 */
public record LiveAnalysisSnapshot(
    Instrument instrument,
    Timeframe timeframe,
    Instant decisionTimestamp,
    Instant captureTimestamp,
    int scoringEngineVersion,
    BigDecimal currentPrice,
    IndicatorSnapshot indicators,
    SmcContext smc,
    OrderFlowContext orderFlow,
    List<OrderFlowEventSummary> momentumWindow,
    List<OrderFlowEventSummary> absorptionWindow,
    List<OrderFlowEventSummary> distributionRecent,
    List<OrderFlowEventSummary> cycleRecent,
    MacroContext macro
) {
    public LiveAnalysisSnapshot {
        Objects.requireNonNull(instrument);
        Objects.requireNonNull(timeframe);
        Objects.requireNonNull(decisionTimestamp);
        Objects.requireNonNull(captureTimestamp);
        if (captureTimestamp.isBefore(decisionTimestamp)) {
            throw new IllegalStateException(
                "captureTimestamp before decisionTimestamp — causality violated");
        }
        // Defensive immutable copies of every list
        momentumWindow      = List.copyOf(Objects.requireNonNullElse(momentumWindow, List.of()));
        absorptionWindow    = List.copyOf(Objects.requireNonNullElse(absorptionWindow, List.of()));
        distributionRecent  = List.copyOf(Objects.requireNonNullElse(distributionRecent, List.of()));
        cycleRecent         = List.copyOf(Objects.requireNonNullElse(cycleRecent, List.of()));
    }
}
