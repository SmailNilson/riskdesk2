package com.riskdesk.domain.engine.strategy.wtx;

import com.riskdesk.domain.engine.indicators.WaveTrendIndicator.WaveTrendResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Pure domain function: evaluates a closed candle against the WTX strategy rules.
 *
 * Faithfully ports Pine Script logic bar-by-bar:
 *   compra   = crossover(wt1, wt2)  AND wt1 <= nsv  (from oversold)
 *   compra_1 = crossover(wt1, wt2)  AND !compras_en_sobre_venta
 *   venta    = crossunder(wt1, wt2) AND wt1 >= nsc  (from overbought)
 *   venta_1  = crossunder(wt1, wt2) AND !ventas_en_sobre_compra
 *
 * No Spring, no JPA — safe to unit-test without a Spring context.
 */
public final class WtxBarEvaluator {

    private WtxBarEvaluator() {}

    /**
     * Evaluates the transition from {@code prev} to {@code curr} WT bars.
     *
     * @param prev      previous bar's WaveTrend result (used to detect crossover)
     * @param curr      current (just-closed) bar's WaveTrend result
     * @param config    strategy configuration
     * @param state     current strategy state (position direction, risk flags)
     * @param candleTs  close timestamp of the current candle (UTC)
     * @return a signal if a compra/venta triggers, otherwise empty
     */
    public static Optional<WtxSignal> evaluate(
            WaveTrendResult prev,
            WaveTrendResult curr,
            WtxConfig config,
            WtxStrategyState state,
            Instant candleTs,
            String candleTimeframe
    ) {
        if (prev == null || curr == null) return Optional.empty();

        boolean crossover  = prev.wt1().compareTo(prev.wt2()) <= 0 && curr.wt1().compareTo(curr.wt2()) > 0;
        boolean crossunder = prev.wt1().compareTo(prev.wt2()) >= 0 && curr.wt1().compareTo(curr.wt2()) < 0;

        boolean isOversold   = curr.wt1().compareTo(config.nsv()) <= 0;
        boolean isOverbought = curr.wt1().compareTo(config.nsc()) >= 0;

        boolean compra   = config.useCompra()  && crossover  && isOversold;
        boolean compra1  = config.useCompra1() && crossover  && !isOversold;
        boolean venta    = config.useVenta()   && crossunder && isOverbought;
        boolean venta1   = config.useVenta1()  && crossunder && !isOverbought;

        boolean longSignal  = compra  || compra1;
        boolean shortSignal = venta   || venta1;

        if (!longSignal && !shortSignal) return Optional.empty();

        boolean maxLossHit     = state.maxLossHit();
        boolean forceCloseWin  = WtxRiskGuard.isForceCloseWindow(candleTs, config);
        boolean canTrade       = WtxRiskGuard.canTrade(maxLossHit, forceCloseWin);

        WtxSignalType signalType;
        String direction;
        WtxAction action;

        if (longSignal) {
            signalType = compra ? WtxSignalType.COMPRA : WtxSignalType.COMPRA_1;
            direction  = "LONG";
            if (!canTrade) {
                action = WtxAction.NONE;
            } else if (state.currentPosition() == WtxPosition.SHORT && config.reverseOnOpp()) {
                action = WtxAction.REVERSE_TO_LONG;
            } else if (state.currentPosition() == WtxPosition.FLAT) {
                action = WtxAction.OPEN_LONG;
            } else {
                action = WtxAction.NONE;
            }
        } else {
            signalType = venta ? WtxSignalType.VENTA : WtxSignalType.VENTA_1;
            direction  = "SHORT";
            if (!canTrade) {
                action = WtxAction.NONE;
            } else if (state.currentPosition() == WtxPosition.LONG && config.reverseOnOpp()) {
                action = WtxAction.REVERSE_TO_SHORT;
            } else if (state.currentPosition() == WtxPosition.FLAT) {
                action = WtxAction.OPEN_SHORT;
            } else {
                action = WtxAction.NONE;
            }
        }

        return Optional.of(new WtxSignal(
                state.instrument(),
                candleTimeframe,
                signalType,
                direction,
                curr.wt1(),
                curr.wt2(),
                canTrade,
                action,
                WtxEnrichmentSnapshot.empty(),
                candleTs
        ));
    }

    /**
     * Extracts the previous and current WaveTrend results from a list of all computed results.
     * Requires at least 2 results.
     */
    public static WaveTrendResult prev(java.util.List<WaveTrendResult> results) {
        if (results.size() < 2) return null;
        return results.get(results.size() - 2);
    }

    public static WaveTrendResult curr(java.util.List<WaveTrendResult> results) {
        if (results.isEmpty()) return null;
        return results.get(results.size() - 1);
    }
}
