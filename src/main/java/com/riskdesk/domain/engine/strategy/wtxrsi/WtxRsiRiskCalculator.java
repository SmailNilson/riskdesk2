package com.riskdesk.domain.engine.strategy.wtxrsi;

import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Builds a {@link WtxRsiRiskPlan} for a freshly emitted signal:
 *   - locates the most-recent <i>confirmed</i> Williams fractal of opposite polarity
 *   - applies the configured tick buffer below/above it
 *   - rejects the trade (returns empty) when no fractal is in range or the
 *     resulting SL would sit on the wrong side of the entry price
 *   - computes the TP if {@link WtxRsiTpMode#R_MULTIPLE} is configured
 *   - sizes the position at {@link WtxRsiConfig#baseContracts()} (Chaikin
 *     confirmation no longer scales the contract count)
 */
public final class WtxRsiRiskCalculator {

    private WtxRsiRiskCalculator() {}

    public static Optional<WtxRsiRiskPlan> build(
            WtxRsiSignal signal,
            List<Candle> candles,
            BigDecimal entryPrice,
            WtxRsiConfig config) {

        Optional<WilliamsFractal.Fractal> swing = (signal.side() == WtxRsiSignal.Side.LONG)
                ? WilliamsFractal.findMostRecentConfirmedLow(
                        candles, signal.barIndex(), config.fractalLeftRight(), config.fractalMaxLookback())
                : WilliamsFractal.findMostRecentConfirmedHigh(
                        candles, signal.barIndex(), config.fractalLeftRight(), config.fractalMaxLookback());

        if (swing.isEmpty()) return Optional.empty();

        BigDecimal buffer = config.tickSize().multiply(BigDecimal.valueOf(config.swingBufferTicks()));
        BigDecimal swingPrice = swing.get().price();
        BigDecimal stop = (signal.side() == WtxRsiSignal.Side.LONG)
                ? roundToTick(swingPrice.subtract(buffer), config.tickSize())
                : roundToTick(swingPrice.add(buffer), config.tickSize());

        // Reject when SL is on the wrong side of entry (happens on whipsaw bars).
        BigDecimal risk;
        if (signal.side() == WtxRsiSignal.Side.LONG) {
            if (stop.compareTo(entryPrice) >= 0) return Optional.empty();
            risk = entryPrice.subtract(stop);
        } else {
            if (stop.compareTo(entryPrice) <= 0) return Optional.empty();
            risk = stop.subtract(entryPrice);
        }

        BigDecimal tp = null;
        if (config.tpMode() == WtxRsiTpMode.R_MULTIPLE
                && config.tpRMultiple() != null
                && config.tpRMultiple().signum() > 0) {
            BigDecimal offset = risk.multiply(config.tpRMultiple());
            BigDecimal raw = (signal.side() == WtxRsiSignal.Side.LONG)
                    ? entryPrice.add(offset)
                    : entryPrice.subtract(offset);
            tp = roundToTick(raw, config.tickSize());
        }

        return Optional.of(new WtxRsiRiskPlan(
                signal.side(),
                config.baseContracts(),
                roundToTick(entryPrice, config.tickSize()),
                stop,
                tp,
                risk,
                swingPrice
        ));
    }

    static BigDecimal roundToTick(BigDecimal price, BigDecimal tickSize) {
        BigDecimal ticks = price.divide(tickSize, 0, RoundingMode.HALF_UP);
        return ticks.multiply(tickSize);
    }
}
