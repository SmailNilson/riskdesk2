package com.riskdesk.domain.trading.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.shared.vo.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Domain service that calculates Profit & Loss for a trade.
 * Extracted from Instrument.calculatePnL to follow Single Responsibility Principle.
 */
public class PnLCalculator {

    public Money calculate(Instrument instrument, BigDecimal entryPrice, BigDecimal exitPrice, int quantity, Side side) {
        BigDecimal priceDiff = exitPrice.subtract(entryPrice);
        if (side == Side.SHORT) {
            priceDiff = priceDiff.negate();
        }
        BigDecimal ticks = priceDiff.divide(instrument.getTickSize(), 0, RoundingMode.HALF_UP);
        BigDecimal pnl = ticks.multiply(instrument.getTickValue()).multiply(BigDecimal.valueOf(quantity));
        return Money.of(pnl);
    }
}
