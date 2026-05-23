package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiBiasSource;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiTpMode;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiZoneMode;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * REST-friendly input for a one-off backtest.
 *
 * Any nullable field is filled from {@code application.properties} defaults
 * when the request is processed — only {@code instrument}, {@code timeframe},
 * {@code from}, and {@code to} are required.
 */
public record WtxRsiBacktestRequest(
        String instrument,
        String timeframe,
        Instant from,
        Instant to,
        // Optional overrides — null falls back to property defaults.
        Integer syncLookbackBars,
        WtxRsiZoneMode zoneMode,
        Integer zoneLookbackBars,
        Integer fractalLeftRight,
        Integer fractalMaxLookback,
        Integer swingBufferTicks,
        WtxRsiTpMode tpMode,
        BigDecimal tpRMultiple,
        Boolean chaikinEnabled,
        WtxRsiBiasSource biasSource
) {}
