package com.riskdesk.domain.engine.strategy.wtx;

import java.math.BigDecimal;

/**
 * Per-(instrument, timeframe) frontend overrides of WTX indicator + stop parameters.
 *
 * <p>Each field is nullable: a {@code null} means "no override — fall back to the global
 * {@link WtxConfig} value (from application.properties)". This lets the operator tune
 * {@code n1 / n2 / signalPeriod} (WaveTrend periods) and {@code slAtrMult} (initial-stop ATR
 * multiple) per panel without touching the global config, persisted independently of the
 * runtime {@link WtxStrategyState}.
 */
public record WtxParamOverride(
        Integer n1,
        Integer n2,
        Integer signalPeriod,
        BigDecimal slAtrMult
) {
    /** No overrides — every effective value falls back to the global config. */
    public static final WtxParamOverride NONE = new WtxParamOverride(null, null, null, null);

    public boolean isEmpty() {
        return n1 == null && n2 == null && signalPeriod == null && slAtrMult == null;
    }
}
