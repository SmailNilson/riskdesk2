package com.riskdesk.domain.alert.service;

import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import com.riskdesk.domain.alert.model.AlertSeverity;
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

class RiskAlertEvaluatorTest {

    private Position makePosition(Instrument instrument, Side side, int qty, BigDecimal entryPrice) {
        return new Position(instrument, side, qty, entryPrice);
    }

    @Test
    void marginExceeded_generatesDangerAlert() {
        // MCL: 450.00 * 100 * 2 = 90,000 exposure
        // Margin = 100,000 -> 90% usage, threshold = 80%
        Position p = makePosition(Instrument.MCL, Side.LONG, 2, new BigDecimal("450.00"));
        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of(p));
        RiskSpecification spec = new RiskSpecification(80.0, 25.0);
        RiskAlertEvaluator evaluator = new RiskAlertEvaluator(spec);

        List<Alert> alerts = evaluator.evaluate(portfolio);

        assertTrue(alerts.stream().anyMatch(a ->
            a.severity() == AlertSeverity.DANGER && a.key().equals("risk:margin")));
    }

    @Test
    void marginWarning_at90PercentOfThreshold_generatesWarningAlert() {
        // Threshold = 80%, warning fires at 80 * 0.9 = 72%
        // MCL: 365.00 * 100 * 2 = 73,000 exposure
        // Margin = 100,000 -> 73.0% usage -> warning
        Position p = makePosition(Instrument.MCL, Side.LONG, 2, new BigDecimal("365.00"));
        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of(p));
        RiskSpecification spec = new RiskSpecification(80.0, 25.0);
        RiskAlertEvaluator evaluator = new RiskAlertEvaluator(spec);

        List<Alert> alerts = evaluator.evaluate(portfolio);

        assertTrue(alerts.stream().anyMatch(a ->
            a.severity() == AlertSeverity.WARNING && a.key().equals("risk:margin:warn")));
    }

    @Test
    void withinSafeLimits_generatesNoAlerts() {
        // 4 equally weighted positions, each ~25% of total exposure (not exceeding 25%)
        // MCL: 10.00 * 100 * 1 = 1,000
        // MGC: 100.00 * 10 * 1 = 1,000
        // E6: 0.00800 * 125000 * 1 = 1,000
        // MNQ: 500.00 * 2 * 1 = 1,000
        // Total exposure = 4,000, Margin = 100,000 -> 4.0% usage, well below 80% threshold
        // Each position = 25% of exposure (not above 25% threshold)
        Position p1 = makePosition(Instrument.MCL, Side.LONG, 1, new BigDecimal("10.00"));
        Position p2 = makePosition(Instrument.MGC, Side.LONG, 1, new BigDecimal("100.00"));
        Position p3 = makePosition(Instrument.E6, Side.LONG, 1, new BigDecimal("0.00800"));
        Position p4 = makePosition(Instrument.MNQ, Side.LONG, 1, new BigDecimal("500.00"));
        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of(p1, p2, p3, p4));
        RiskSpecification spec = new RiskSpecification(80.0, 25.0);
        RiskAlertEvaluator evaluator = new RiskAlertEvaluator(spec);

        List<Alert> alerts = evaluator.evaluate(portfolio);

        assertTrue(alerts.isEmpty());
    }

    @Test
    void concentratedPosition_generatesWarningWithInstrumentName() {
        // Single position = 100% of exposure -> above 25% threshold
        Position p = makePosition(Instrument.MNQ, Side.LONG, 1, new BigDecimal("18250.00"));
        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of(p));
        RiskSpecification spec = new RiskSpecification(80.0, 25.0);
        RiskAlertEvaluator evaluator = new RiskAlertEvaluator(spec);

        List<Alert> alerts = evaluator.evaluate(portfolio);

        assertTrue(alerts.stream().anyMatch(a ->
            a.severity() == AlertSeverity.WARNING
                && a.category() == AlertCategory.RISK
                && a.instrument() != null
                && a.instrument().equals("MNQ")));
    }

    @Test
    void multipleConcentratedPositions_generateMultipleAlerts() {
        // Both positions are > 25% of total exposure
        Position p1 = makePosition(Instrument.MCL, Side.LONG, 2, new BigDecimal("62.40"));
        Position p2 = makePosition(Instrument.MNQ, Side.LONG, 1, new BigDecimal("18250.00"));
        Portfolio portfolio = new Portfolio(Money.of("100000.00"), List.of(p1, p2));
        RiskSpecification spec = new RiskSpecification(80.0, 25.0);
        RiskAlertEvaluator evaluator = new RiskAlertEvaluator(spec);

        List<Alert> alerts = evaluator.evaluate(portfolio);

        long concentrationAlerts = alerts.stream()
            .filter(a -> a.key().startsWith("risk:concentration:"))
            .count();
        assertEquals(2, concentrationAlerts);
    }
}
