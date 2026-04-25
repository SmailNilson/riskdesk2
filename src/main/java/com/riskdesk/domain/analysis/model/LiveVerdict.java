package com.riskdesk.domain.analysis.model;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Timeframe;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Top-level result of one live analysis cycle. Combines the directional bias
 * with the list of probabilistic scenarios. Persisted verbatim into
 * {@code verdict_records} for replay and backtest determinism.
 */
public record LiveVerdict(
    Instrument instrument,
    Timeframe timeframe,
    Instant decisionTimestamp,
    int scoringEngineVersion,
    BigDecimal currentPrice,
    DirectionalBias bias,
    List<TradeScenario> scenarios,
    Instant validUntil
) {
    public LiveVerdict {
        Objects.requireNonNull(instrument);
        Objects.requireNonNull(timeframe);
        Objects.requireNonNull(decisionTimestamp);
        Objects.requireNonNull(bias);
        scenarios = List.copyOf(Objects.requireNonNullElse(scenarios, List.of()));
        Objects.requireNonNull(validUntil);
    }
}
