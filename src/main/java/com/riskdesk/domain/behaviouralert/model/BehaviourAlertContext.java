package com.riskdesk.domain.behaviouralert.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Input context passed to each {@link com.riskdesk.domain.behaviouralert.rule.BehaviourAlertRule}.
 * Built by the application layer from {@code IndicatorSnapshot}.
 */
public record BehaviourAlertContext(
    String instrument,
    String timeframe,
    BigDecimal lastPrice,
    BigDecimal ema50,
    BigDecimal ema200,
    List<SrLevel> srLevels,
    Instant lastCandleTimestamp
) {
    /**
     * A named support/resistance level for proximity evaluation.
     * levelType values: "EQH", "EQL", "STRONG_HIGH", "STRONG_LOW", "WEAK_HIGH", "WEAK_LOW"
     */
    public record SrLevel(String levelType, BigDecimal price) {}
}
