package com.riskdesk.domain.alert.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record IndicatorAlertSnapshot(
    String emaCrossover,
    BigDecimal rsi,
    String rsiSignal,
    String macdCrossover,
    String lastBreakType,
    String lastInternalBreakType,
    String lastSwingBreakType,
    BigDecimal wtWt1,
    String wtCrossover,
    String wtSignal,
    BigDecimal vwap,
    List<OrderBlockZone> activeOrderBlocks,
    /** UC-SMC-009: real OB lifecycle events (MITIGATION / INVALIDATION) on the last bar. */
    List<OrderBlockEvent> recentObEvents,
    /** Timestamp of the last closed candle used to compute this snapshot (Rule 4: candle close guard). */
    Instant lastCandleTimestamp
) {
    public record OrderBlockZone(String type, BigDecimal high, BigDecimal low) {}

    /** OB lifecycle event for alert evaluation. */
    public record OrderBlockEvent(String eventType, String obType, BigDecimal high, BigDecimal low) {}
}
