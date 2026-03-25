package com.riskdesk.domain.trading;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Position;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.shared.vo.Money;
import com.riskdesk.domain.trading.aggregate.Portfolio;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PortfolioTest {

    /**
     * Helper to create a Position with known values for testing.
     * Uses the 4-arg constructor + setters to configure unrealized P&L.
     */
    private Position makePosition(Instrument instrument, Side side, int qty,
                                   BigDecimal entryPrice, BigDecimal unrealizedPnL) {
        Position p = new Position(instrument, side, qty, entryPrice);
        p.setUnrealizedPnL(unrealizedPnL);
        return p;
    }

    @Test
    void totalUnrealizedPnL_sumsTwoPositions() {
        // MCL LONG: unrealized = $200.00
        // MNQ LONG: unrealized = $20.00
        // Total = $220.00
        Position p1 = makePosition(Instrument.MCL, Side.LONG, 2,
                new BigDecimal("62.40"), new BigDecimal("200.00"));
        Position p2 = makePosition(Instrument.MNQ, Side.LONG, 1,
                new BigDecimal("18250.00"), new BigDecimal("20.00"));

        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of(p1, p2));

        assertEquals(Money.of("220.00"), portfolio.totalUnrealizedPnL());
    }

    @Test
    void totalExposure_sumsAbsEntryTimesMultiplierTimesQty() {
        // MCL: 62.40 * 100 * 2 = 12,480.00
        // MNQ: 18250.00 * 2 * 1 = 36,500.00
        // Total = 48,980.00
        Position p1 = makePosition(Instrument.MCL, Side.LONG, 2,
                new BigDecimal("62.40"), BigDecimal.ZERO);
        Position p2 = makePosition(Instrument.MNQ, Side.LONG, 1,
                new BigDecimal("18250.00"), BigDecimal.ZERO);

        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of(p1, p2));

        assertEquals(Money.of("48980.00"), portfolio.totalExposure());
    }

    @Test
    void marginUsedPercent_calculatesCorrectly() {
        // Exposure = 48,980.00, Margin = 100,000.00
        // Percent = (48980 / 100000) * 100 = 49.0%
        Position p1 = makePosition(Instrument.MCL, Side.LONG, 2,
                new BigDecimal("62.40"), BigDecimal.ZERO);
        Position p2 = makePosition(Instrument.MNQ, Side.LONG, 1,
                new BigDecimal("18250.00"), BigDecimal.ZERO);

        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of(p1, p2));

        assertEquals(new BigDecimal("49.0"), portfolio.marginUsedPercent());
    }

    @Test
    void isOverMarginThreshold_aboveThreshold_returnsTrue() {
        // Margin used = 90%, threshold = 80% -> over
        // Need exposure = 90,000 with margin = 100,000
        // MCL: entry price such that entryPrice * 100 * qty = 90,000
        //   e.g., entryPrice = 450.00, qty = 2 -> 450 * 100 * 2 = 90,000
        Position p = makePosition(Instrument.MCL, Side.LONG, 2,
                new BigDecimal("450.00"), BigDecimal.ZERO);

        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of(p));

        assertTrue(portfolio.isOverMarginThreshold(new BigDecimal("80")));
    }

    @Test
    void isOverMarginThreshold_belowThreshold_returnsFalse() {
        // Margin used = 49.0%, threshold = 80% -> not over
        Position p1 = makePosition(Instrument.MCL, Side.LONG, 2,
                new BigDecimal("62.40"), BigDecimal.ZERO);
        Position p2 = makePosition(Instrument.MNQ, Side.LONG, 1,
                new BigDecimal("18250.00"), BigDecimal.ZERO);

        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of(p1, p2));

        assertFalse(portfolio.isOverMarginThreshold(new BigDecimal("80")));
    }

    @Test
    void positionsExceedingConcentration_returnsConcentratedPositions() {
        // MCL exposure = 12,480 / 48,980 = 25.48% -> below 30%
        // MNQ exposure = 36,500 / 48,980 = 74.52% -> above 30%
        Position p1 = makePosition(Instrument.MCL, Side.LONG, 2,
                new BigDecimal("62.40"), BigDecimal.ZERO);
        Position p2 = makePosition(Instrument.MNQ, Side.LONG, 1,
                new BigDecimal("18250.00"), BigDecimal.ZERO);

        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of(p1, p2));

        List<Position> concentrated = portfolio.positionsExceedingConcentration(new BigDecimal("30"));
        assertEquals(1, concentrated.size());
        assertEquals(Instrument.MNQ, concentrated.get(0).getInstrument());
    }

    @Test
    void emptyPositions_totalUnrealizedPnL_isZero() {
        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of());

        assertEquals(Money.ZERO, portfolio.totalUnrealizedPnL());
    }

    @Test
    void emptyPositions_totalExposure_isZero() {
        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of());

        assertEquals(Money.ZERO, portfolio.totalExposure());
    }

    @Test
    void emptyPositions_marginUsedPercent_isZero() {
        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of());

        assertEquals(new BigDecimal("0.0"), portfolio.marginUsedPercent());
    }

    @Test
    void openPositionCount_returnsCorrectCount() {
        Position p1 = makePosition(Instrument.MCL, Side.LONG, 1,
                new BigDecimal("62.40"), BigDecimal.ZERO);
        Position p2 = makePosition(Instrument.MNQ, Side.LONG, 1,
                new BigDecimal("18250.00"), BigDecimal.ZERO);

        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of(p1, p2));

        assertEquals(2, portfolio.openPositionCount());
    }

    @Test
    void accountMargin_returnsCorrectValue() {
        Portfolio portfolio = new Portfolio(Money.of("50000.00"), List.of());

        assertEquals(Money.of("50000.00"), portfolio.accountMargin());
    }

    @Test
    void totalUnrealizedPnL_handlesNullUnrealizedPnL() {
        // Position with null unrealized P&L should be treated as $0
        Position p = new Position(Instrument.MCL, Side.LONG, 1, new BigDecimal("62.40"));
        // unrealizedPnL is null by default (not set)

        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of(p));

        assertEquals(Money.ZERO, portfolio.totalUnrealizedPnL());
    }
}
