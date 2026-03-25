package com.riskdesk.domain.alert.model;

import java.math.BigDecimal;
import java.util.List;

public record IndicatorAlertSnapshot(
    String emaCrossover,
    BigDecimal rsi,
    String rsiSignal,
    String macdCrossover,
    String lastBreakType,
    BigDecimal wtWt1,
    String wtCrossover,
    String wtSignal,
    BigDecimal vwap,
    List<OrderBlockZone> activeOrderBlocks
) {
    public record OrderBlockZone(String type, BigDecimal high, BigDecimal low) {}
}
