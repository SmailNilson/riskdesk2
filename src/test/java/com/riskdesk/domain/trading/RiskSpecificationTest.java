package com.riskdesk.domain.trading;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Position;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.shared.vo.Money;
import com.riskdesk.domain.trading.aggregate.Portfolio;
import com.riskdesk.domain.trading.service.RiskSpecification;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RiskSpecificationTest {

    private Position makePosition(Instrument instrument, Side side, int qty, BigDecimal entryPrice) {
        return new Position(instrument, side, qty, entryPrice);
    }

    @Test
    void isMarginExceeded_whenUsageAboveMax_returnsTrue() {
        // MCL: 450.00 * 100 * 2 = 90,000 exposure
        // Margin = 100,000 -> 90% usage, threshold = 80%
        Position p = makePosition(Instrument.MCL, Side.LONG, 2, new BigDecimal("450.00"));
        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of(p));

        RiskSpecification spec = new RiskSpecification(80.0, 25.0);

        assertTrue(spec.isMarginExceeded(portfolio));
    }

    @Test
    void isMarginExceeded_whenUsageBelowMax_returnsFalse() {
        // MCL: 62.40 * 100 * 2 = 12,480 + MNQ: 18250 * 2 * 1 = 36,500
        // Total = 48,980, Margin = 100,000 -> 49.0%, threshold = 80%
        Position p1 = makePosition(Instrument.MCL, Side.LONG, 2, new BigDecimal("62.40"));
        Position p2 = makePosition(Instrument.MNQ, Side.LONG, 1, new BigDecimal("18250.00"));
        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of(p1, p2));

        RiskSpecification spec = new RiskSpecification(80.0, 25.0);

        assertFalse(spec.isMarginExceeded(portfolio));
    }

    @Test
    void isMarginWarning_at90PercentOfThreshold_returnsTrue() {
        // Threshold = 80%, warning fires at 80 * 0.9 = 72%
        // MCL: 365.00 * 100 * 2 = 73,000 exposure
        // Margin = 100,000 -> 73.0% usage
        // 73 > 72 (warning threshold) AND 73 < 80 (not exceeded) -> warning = true
        Position p = makePosition(Instrument.MCL, Side.LONG, 2, new BigDecimal("365.00"));
        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of(p));

        RiskSpecification spec = new RiskSpecification(80.0, 25.0);

        assertTrue(spec.isMarginWarning(portfolio));
    }

    @Test
    void isMarginWarning_whenWellBelow_returnsFalse() {
        // Usage = 49.0%, warning threshold = 72% -> false
        Position p1 = makePosition(Instrument.MCL, Side.LONG, 2, new BigDecimal("62.40"));
        Position p2 = makePosition(Instrument.MNQ, Side.LONG, 1, new BigDecimal("18250.00"));
        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of(p1, p2));

        RiskSpecification spec = new RiskSpecification(80.0, 25.0);

        assertFalse(spec.isMarginWarning(portfolio));
    }

    @Test
    void concentratedPositions_returnsPositionsExceedingMax() {
        // MCL: 12,480 / 48,980 = 25.48% -> above 25% threshold
        // MNQ: 36,500 / 48,980 = 74.52% -> above 25% threshold
        Position p1 = makePosition(Instrument.MCL, Side.LONG, 2, new BigDecimal("62.40"));
        Position p2 = makePosition(Instrument.MNQ, Side.LONG, 1, new BigDecimal("18250.00"));
        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of(p1, p2));

        RiskSpecification spec = new RiskSpecification(80.0, 25.0);

        List<Position> concentrated = spec.concentratedPositions(portfolio);
        assertEquals(2, concentrated.size());
    }
}
