package com.riskdesk.domain.trading.service;

import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure-domain validator that enforces position sizing limits BEFORE a broker order
 * is submitted.
 *
 * <p>Prior to this validator, {@code ExecutionManagerService.ensureExecutionCreated()}
 * accepted any {@code command.quantity()} without comparing the resulting $-risk
 * against a portfolio budget. A user could request 100 contracts MNQ on a 5-point
 * stop-loss ({@code 100 * 5 / 0.25 * 0.50 = $1,000 risk}) unchecked.
 *
 * <p>The validator computes the <em>worst-case $ loss</em> if the stop-loss triggers:
 * <pre>
 *   riskUsd = |entry - stopLoss| / tickSize * tickValue * quantity
 * </pre>
 * and rejects the order if it exceeds {@code maxRiskPerTradeUsd} or if
 * {@code quantity > maxQuantityPerOrder}.
 *
 * <p>This is a domain service — no Spring dependency — so it can be unit-tested
 * and reused by simulation, backtest, and live execution paths.
 */
public final class PositionSizeValidator {

    private final BigDecimal maxRiskPerTradeUsd;
    private final int maxQuantityPerOrder;

    public PositionSizeValidator(double maxRiskPerTradeUsd, int maxQuantityPerOrder) {
        if (maxRiskPerTradeUsd <= 0) {
            throw new IllegalArgumentException("maxRiskPerTradeUsd must be > 0");
        }
        if (maxQuantityPerOrder < 1) {
            throw new IllegalArgumentException("maxQuantityPerOrder must be >= 1");
        }
        this.maxRiskPerTradeUsd = BigDecimal.valueOf(maxRiskPerTradeUsd);
        this.maxQuantityPerOrder = maxQuantityPerOrder;
    }

    /**
     * Validates a proposed order. Throws {@link PositionSizeExceededException} on violation.
     *
     * @param instrument the traded instrument (tick size + tick value define $-risk)
     * @param quantity   number of contracts
     * @param entry      proposed entry price (non-null, positive)
     * @param stopLoss   proposed stop-loss price (non-null, positive, {@code != entry})
     */
    public void validate(Instrument instrument, int quantity,
                         BigDecimal entry, BigDecimal stopLoss) {
        if (instrument == null) {
            throw new IllegalArgumentException("instrument is required");
        }
        if (quantity < 1) {
            throw new PositionSizeExceededException(
                "quantity must be >= 1 (got " + quantity + ")",
                quantity, BigDecimal.ZERO, maxRiskPerTradeUsd, maxQuantityPerOrder);
        }
        if (quantity > maxQuantityPerOrder) {
            throw new PositionSizeExceededException(
                "quantity " + quantity + " exceeds max-quantity-per-order " + maxQuantityPerOrder,
                quantity, BigDecimal.ZERO, maxRiskPerTradeUsd, maxQuantityPerOrder);
        }
        if (entry == null || stopLoss == null) {
            throw new IllegalArgumentException("entry and stopLoss are required");
        }
        if (entry.signum() <= 0 || stopLoss.signum() <= 0) {
            throw new IllegalArgumentException("entry and stopLoss must be positive");
        }
        if (entry.compareTo(stopLoss) == 0) {
            throw new PositionSizeExceededException(
                "entry and stopLoss are identical — undefined risk",
                quantity, BigDecimal.ZERO, maxRiskPerTradeUsd, maxQuantityPerOrder);
        }

        BigDecimal riskUsd = computeRiskUsd(instrument, quantity, entry, stopLoss);
        if (riskUsd.compareTo(maxRiskPerTradeUsd) > 0) {
            throw new PositionSizeExceededException(
                String.format(
                    "$-risk %.2f exceeds max-risk-per-trade %.2f "
                        + "(instrument=%s qty=%d entry=%s sl=%s)",
                    riskUsd, maxRiskPerTradeUsd, instrument.name(), quantity, entry, stopLoss),
                quantity, riskUsd, maxRiskPerTradeUsd, maxQuantityPerOrder);
        }
    }

    /**
     * Computes the $-risk of the position: {@code |entry - sl| / tickSize * tickValue * quantity}.
     * Exposed for callers who want to preview the risk without throwing (e.g. UI badges).
     */
    public static BigDecimal computeRiskUsd(Instrument instrument, int quantity,
                                            BigDecimal entry, BigDecimal stopLoss) {
        BigDecimal priceMove = entry.subtract(stopLoss).abs();
        BigDecimal ticks = priceMove.divide(instrument.getTickSize(), 6, RoundingMode.HALF_UP);
        return ticks.multiply(instrument.getTickValue())
            .multiply(BigDecimal.valueOf(quantity))
            .setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal maxRiskPerTradeUsd() {
        return maxRiskPerTradeUsd;
    }

    public int maxQuantityPerOrder() {
        return maxQuantityPerOrder;
    }
}
