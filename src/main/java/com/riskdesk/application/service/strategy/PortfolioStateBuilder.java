package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.PortfolioSummary;
import com.riskdesk.application.service.PositionService;
import com.riskdesk.domain.engine.strategy.model.PortfolioState;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bridges the live {@link PortfolioSummary} onto the strategy-local
 * {@link PortfolioState}. Lives in application-layer because it imports an app
 * DTO; the strategy package stays independent.
 *
 * <p>Returns {@link PortfolioState#unknown()} on any failure — the
 * {@code RiskGateAgent} abstains on unknown state, so a broker outage doesn't
 * forcibly block trading. If you want a fail-closed posture, wrap this builder
 * in a supervisor that turns repeated failures into a veto.
 */
@Component
public class PortfolioStateBuilder {

    private static final Logger log = LoggerFactory.getLogger(PortfolioStateBuilder.class);

    private final PositionService positionService;

    public PortfolioStateBuilder(PositionService positionService) {
        this.positionService = positionService;
    }

    public PortfolioState build(Instrument instrument) {
        try {
            PortfolioSummary summary = positionService.getPortfolioSummary();
            if (summary == null) return PortfolioState.unknown();

            boolean correlated = summary.openPositions() != null
                && summary.openPositions().stream()
                    .anyMatch(p -> p != null
                        && p.instrument() != null
                        && p.instrument().equals(instrument.name())
                        && p.open());

            double marginPct = summary.marginUsedPct() != null
                ? summary.marginUsedPct().doubleValue()
                : 0;

            // Drawdown: mirror the legacy AgentOrchestratorService convention —
            // unrealized loss as a fraction of total exposure. A dedicated daily
            // P&L ledger will replace this approximation when it lands.
            double drawdown = 0;
            if (summary.totalUnrealizedPnL() != null
                    && summary.totalUnrealizedPnL().doubleValue() < 0
                    && summary.totalExposure() != null
                    && summary.totalExposure().doubleValue() > 0) {
                drawdown = Math.abs(summary.totalUnrealizedPnL().doubleValue()
                    / summary.totalExposure().doubleValue()) * 100;
            }

            return new PortfolioState(
                summary.totalUnrealizedPnL() != null
                    ? summary.totalUnrealizedPnL().doubleValue() : 0,
                drawdown,
                (int) summary.openPositionCount(),
                correlated,
                marginPct
            );
        } catch (Exception e) {
            log.debug("PortfolioStateBuilder failed for {}: {}", instrument, e.getMessage());
            return PortfolioState.unknown();
        }
    }
}
