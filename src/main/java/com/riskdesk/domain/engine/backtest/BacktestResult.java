package com.riskdesk.domain.engine.backtest;

import java.util.List;

public record BacktestResult(
    String strategy,
    String instrument,
    String timeframe,
    int totalCandles,
    double initialCapital,
    double finalCapital,
    double totalPnl,
    double totalReturnPct,
    int totalTrades,
    int wins,
    int losses,
    double winRate,
    double avgWin,
    double avgLoss,
    double profitFactor,
    double maxDrawdown,
    double maxDrawdownPct,
    double sharpeRatio,
    List<BacktestTrade> trades,
    List<Double> equityCurve,
    List<SignalDebug> signals,
    DataAudit dataAudit,
    List<DebugEvent> debugEvents
) {
    public record SignalDebug(String time, String type, double price, double wt1, double wt2) {}

    public record DataAudit(
        int loadedCandles,
        int evaluatedCandles,
        int duplicateCandles,
        int outOfOrderPairs,
        int alignedCandles,
        int misalignedCandles,
        int gapCount,
        int suspiciousGapCount,
        String maxGap,
        String firstCandleTime,
        String requestedEvaluationStartTime,
        String evaluationStartTime,
        String lastCandleTime,
        String timezone,
        int requestedWarmupBars,
        int availableWarmupBars,
        boolean sufficientWarmup,
        boolean adjustedEvaluationStart,
        List<String> warnings
    ) {}

    public record DebugEvent(
        String time,
        String event,
        String side,
        double price,
        String reason,
        Double wt1,
        Double wt2,
        Double ema,
        Double atr,
        Double stopPrice
    ) {}

    public static BacktestResult empty(String strategy, String instrument, String timeframe, double capital) {
        return new BacktestResult(strategy, instrument, timeframe, 0, capital, capital, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(), null, List.of());
    }
}
