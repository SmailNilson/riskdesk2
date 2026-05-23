package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiBacktestEngine;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiConfig;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiMetrics;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiTrade;

import java.util.List;

/**
 * Backtest result returned by {@link WtxRsiBacktestService}.
 * The full equity curve is included so the UI can render a P&L chart;
 * trim it client-side if size becomes an issue.
 */
public record WtxRsiBacktestResponse(
        String instrument,
        String timeframe,
        int barsLoaded,
        WtxRsiConfig config,
        WtxRsiMetrics metrics,
        List<WtxRsiTrade> trades,
        List<WtxRsiBacktestEngine.EquityPoint> equityCurve
) {}
