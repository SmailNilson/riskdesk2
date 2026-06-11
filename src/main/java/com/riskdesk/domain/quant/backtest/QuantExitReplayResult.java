package com.riskdesk.domain.quant.backtest;

import java.util.List;
import java.util.Map;

/**
 * Aggregated outcome of one {@link QuantExitReplayEngine} run.
 *
 * @param overall        whole-run aggregate
 * @param byInstrument   per-instrument aggregates
 * @param byDirection    LONG / SHORT aggregates
 * @param byExitReason   SL / TP / AVOID / EOD / DATA_END aggregates
 * @param byDay          per-ET-trading-day aggregates (key {@code yyyy-MM-dd})
 * @param skippedHtf     entries dropped by the HTF filter
 * @param skippedNoData  entries dropped for missing candles/ATR around entry
 * @param trades         per-trade outcomes (recorded id → replayed exit)
 */
public record QuantExitReplayResult(
    Bucket overall,
    Map<String, Bucket> byInstrument,
    Map<String, Bucket> byDirection,
    Map<String, Bucket> byExitReason,
    Map<String, Bucket> byDay,
    int skippedHtf,
    int skippedNoData,
    List<ReplayedTrade> trades
) {

    /**
     * One aggregate cell.
     *
     * @param n           replayed trades
     * @param wins        trades with positive points
     * @param winRatePct  wins / n in % (null when n == 0)
     * @param grossUsd    Σ points × contract multiplier
     * @param netUsd      grossUsd − n × commission
     * @param expectancyR mean P&L per trade in R (points / SL distance)
     */
    public record Bucket(int n, int wins, Double winRatePct, double grossUsd, double netUsd, Double expectancyR) {}

    /**
     * One replayed trade outcome.
     *
     * @param recordedId  id of the recorded simulation row
     * @param instrument  instrument name
     * @param direction   LONG / SHORT
     * @param exitReason  SL / TP / AVOID / EOD / DATA_END
     * @param pnlPoints   signed points captured by the replay
     * @param pnlUsd      pnlPoints × contract multiplier
     * @param riskPoints  SL distance used (R denominator)
     */
    public record ReplayedTrade(
        long recordedId,
        String instrument,
        String direction,
        String exitReason,
        double pnlPoints,
        double pnlUsd,
        double riskPoints
    ) {}
}
