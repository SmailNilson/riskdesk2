package com.riskdesk.application.dto;

import java.math.BigDecimal;
import java.util.List;

public record IndicatorSeriesSnapshot(
    String instrument,
    String timeframe,
    List<LinePoint> ema9,
    List<LinePoint> ema50,
    List<LinePoint> ema200,
    List<BollingerPoint> bollingerBands,
    List<WaveTrendPoint> waveTrend
) {
    public record LinePoint(long time, BigDecimal value) {}

    public record BollingerPoint(long time, BigDecimal upper, BigDecimal lower) {}

    public record WaveTrendPoint(long time, BigDecimal wt1, BigDecimal wt2, BigDecimal diff) {}
}
