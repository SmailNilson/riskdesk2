package com.riskdesk.domain.alert.service;

import com.riskdesk.domain.alert.model.*;
import com.riskdesk.domain.model.Position;
import com.riskdesk.domain.trading.aggregate.Portfolio;
import com.riskdesk.domain.trading.service.RiskSpecification;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates a Portfolio against risk thresholds and produces domain Alert objects.
 * Extracted from the monolithic AlertService.checkRisk() method.
 */
public class RiskAlertEvaluator {

    private final RiskSpecification riskSpec;

    public RiskAlertEvaluator(RiskSpecification riskSpec) {
        this.riskSpec = riskSpec;
    }

    public List<Alert> evaluate(Portfolio portfolio) {
        List<Alert> alerts = new ArrayList<>();

        BigDecimal marginPct = portfolio.marginUsedPercent();

        if (riskSpec.isMarginExceeded(portfolio)) {
            alerts.add(new Alert("risk:margin", AlertSeverity.DANGER,
                String.format("Margin usage at %.1f%% — exceeds threshold", marginPct.doubleValue()),
                AlertCategory.RISK, null));
        } else if (riskSpec.isMarginWarning(portfolio)) {
            alerts.add(new Alert("risk:margin:warn", AlertSeverity.WARNING,
                String.format("Margin usage approaching limit: %.1f%%", marginPct.doubleValue()),
                AlertCategory.RISK, null));
        }

        // Concentration checks
        List<Position> concentrated = riskSpec.concentratedPositions(portfolio);
        for (Position pos : concentrated) {
            BigDecimal notional = pos.getEntryPrice()
                .multiply(pos.getInstrument().getContractMultiplier())
                .multiply(BigDecimal.valueOf(pos.getQuantity()));
            BigDecimal positionPct = notional.divide(portfolio.totalExposure().amount(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
            alerts.add(new Alert("risk:concentration:" + pos.getId(), AlertSeverity.WARNING,
                String.format("%s position is %.1f%% of total exposure",
                    pos.getInstrument().getDisplayName(), positionPct.doubleValue()),
                AlertCategory.RISK, pos.getInstrument().name()));
        }

        return alerts;
    }
}
