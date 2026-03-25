package com.riskdesk.domain.engine.indicators;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Generic result from any indicator computation.
 * The values map holds named outputs (e.g., "ema9" -> 4701.5, "signal" -> "BUY").
 */
public record IndicatorResult(
    String indicatorName,
    Instant timestamp,
    Map<String, Object> values
) {
    public BigDecimal getDecimal(String key) {
        Object v = values.get(key);
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Double d) return BigDecimal.valueOf(d);
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    public String getString(String key) {
        Object v = values.get(key);
        return v != null ? v.toString() : null;
    }

    public Boolean getBool(String key) {
        Object v = values.get(key);
        return v instanceof Boolean b ? b : null;
    }
}
