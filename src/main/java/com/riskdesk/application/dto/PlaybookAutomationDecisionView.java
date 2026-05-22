package com.riskdesk.application.dto;

import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.domain.playbook.automation.PlaybookDecision;
import com.riskdesk.domain.playbook.automation.PlaybookRoutingOutcome;

import java.math.BigDecimal;
import java.time.Instant;

public record PlaybookAutomationDecisionView(
    Long id,
    Instant createdAt,
    String instrument,
    String timeframe,
    String direction,
    Integer checklistScore,
    Integer paperThreshold,
    Integer liveThreshold,
    Boolean autoIbkrEnabled,
    Integer quantity,
    String brokerAccountId,
    PlaybookRoutingOutcome routingOutcome,
    String routingErrorMessage,
    Long executionId,
    TradeSimulationStatus simulationStatus,
    BigDecimal simulationPnl,
    BigDecimal pnl,
    BigDecimal rrRatio,
    String verdict,
    String priceSource,
    Instant priceTimestamp,
    Instant evaluatedCandleTs,
    PlaybookAutomationProfitabilitySummaryView profitabilitySummary
) {
    public static PlaybookAutomationDecisionView from(PlaybookDecision decision,
                                                      Integer paperThreshold,
                                                      Integer liveThreshold,
                                                      Boolean autoIbkrEnabled,
                                                      Integer quantity,
                                                      String brokerAccountId,
                                                      TradeSimulationStatus simulationStatus,
                                                      BigDecimal simulationPnl,
                                                      PlaybookAutomationProfitabilitySummaryView summary) {
        return new PlaybookAutomationDecisionView(
            decision.id(),
            decision.createdAt(),
            decision.instrument(),
            decision.timeframe(),
            decision.direction(),
            decision.checklistScore(),
            paperThreshold,
            liveThreshold,
            autoIbkrEnabled,
            quantity,
            brokerAccountId,
            decision.routingOutcome(),
            decision.routingErrorMessage(),
            decision.executionId(),
            simulationStatus,
            simulationPnl,
            simulationPnl,
            decision.rrRatio(),
            decision.verdict(),
            decision.priceSource(),
            decision.priceTimestamp(),
            decision.evaluatedCandleTs(),
            summary
        );
    }
}
