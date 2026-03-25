package com.riskdesk.domain.trading;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.shared.vo.Money;
import com.riskdesk.domain.trading.service.PnLCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class PnLCalculatorTest {

    private PnLCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new PnLCalculator();
    }

    @Test
    void mcl_long_profitableTrade_returnsCorrectPnL() {
        // MCL LONG: entry 62.40, exit 63.40, qty 2
        // ticks = (63.40 - 62.40) / 0.01 = 100, P&L = 100 * $1.00 * 2 = $200.00
        Money result = calculator.calculate(
                Instrument.MCL,
                new BigDecimal("62.40"),
                new BigDecimal("63.40"),
                2,
                Side.LONG
        );
        assertEquals(Money.of("200.00"), result);
    }

    @Test
    void mcl_short_profitableTrade_returnsCorrectPnL() {
        // MCL SHORT: entry 62.40, exit 61.40, qty 2
        // priceDiff = 61.40 - 62.40 = -1.00, negated for SHORT = 1.00
        // ticks = 1.00 / 0.01 = 100, P&L = 100 * $1.00 * 2 = $200.00
        Money result = calculator.calculate(
                Instrument.MCL,
                new BigDecimal("62.40"),
                new BigDecimal("61.40"),
                2,
                Side.SHORT
        );
        assertEquals(Money.of("200.00"), result);
    }

    @Test
    void mcl_short_losingTrade_returnsNegativePnL() {
        // MCL SHORT loss: entry 62.40, exit 63.40, qty 2
        // priceDiff = 63.40 - 62.40 = 1.00, negated for SHORT = -1.00
        // ticks = -1.00 / 0.01 = -100, P&L = -100 * $1.00 * 2 = -$200.00
        Money result = calculator.calculate(
                Instrument.MCL,
                new BigDecimal("62.40"),
                new BigDecimal("63.40"),
                2,
                Side.SHORT
        );
        assertEquals(Money.of("-200.00"), result);
    }

    @Test
    void mgc_long_profitableTrade_returnsCorrectPnL() {
        // MGC LONG: entry 2040.00, exit 2050.00, qty 1
        // ticks = (2050.00 - 2040.00) / 0.10 = 100, P&L = 100 * $1.00 * 1 = $100.00
        Money result = calculator.calculate(
                Instrument.MGC,
                new BigDecimal("2040.00"),
                new BigDecimal("2050.00"),
                1,
                Side.LONG
        );
        assertEquals(Money.of("100.00"), result);
    }

    @Test
    void e6_long_profitableTrade_returnsCorrectPnL() {
        // E6 LONG: entry 1.08200, exit 1.08300, qty 1
        // ticks = (0.00100) / 0.00005 = 20, P&L = 20 * $6.25 * 1 = $125.00
        Money result = calculator.calculate(
                Instrument.E6,
                new BigDecimal("1.08200"),
                new BigDecimal("1.08300"),
                1,
                Side.LONG
        );
        assertEquals(Money.of("125.00"), result);
    }

    @Test
    void mnq_long_profitableTrade_returnsCorrectPnL() {
        // MNQ LONG: entry 18250.00, exit 18260.00, qty 1
        // ticks = (10.0) / 0.25 = 40, P&L = 40 * $0.50 * 1 = $20.00
        Money result = calculator.calculate(
                Instrument.MNQ,
                new BigDecimal("18250.00"),
                new BigDecimal("18260.00"),
                1,
                Side.LONG
        );
        assertEquals(Money.of("20.00"), result);
    }

    @Test
    void zeroMove_returnsZeroPnL() {
        // Zero move: entry = exit -> P&L = $0.00
        Money result = calculator.calculate(
                Instrument.MCL,
                new BigDecimal("62.40"),
                new BigDecimal("62.40"),
                2,
                Side.LONG
        );
        assertEquals(Money.ZERO, result);
    }
}
