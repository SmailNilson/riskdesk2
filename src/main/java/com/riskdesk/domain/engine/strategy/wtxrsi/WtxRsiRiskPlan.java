package com.riskdesk.domain.engine.strategy.wtxrsi;

import java.math.BigDecimal;

/**
 * Concrete order plan for a {@link WtxRsiSignal}: entry, stop, optional TP and size.
 * {@code takeProfit} is null when {@link WtxRsiTpMode#REVERSAL} is selected.
 *
 * {@code initialRiskPerContract} is preserved so trailing logic and R-multiple
 * monitoring can reference the original risk after the SL is moved.
 */
public record WtxRsiRiskPlan(
        WtxRsiSignal.Side side,
        int contracts,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal takeProfit,            // nullable
        BigDecimal initialRiskPerContract,
        BigDecimal swingReference         // raw fractal price before buffer
) {}
