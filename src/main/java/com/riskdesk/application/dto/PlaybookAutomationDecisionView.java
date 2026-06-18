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
    BigDecimal entryPrice,
    BigDecimal stopLoss,
    BigDecimal takeProfit1,
    BigDecimal takeProfit2,
    PlaybookRoutingOutcome routingOutcome,
    String routingErrorMessage,
    Long executionId,
    TradeSimulationStatus simulationStatus,
    BigDecimal simulationPnl,
    BigDecimal pnl,
    /** Realistic fill the live broker would chase to on a late entry (else the plan entry). */
    BigDecimal realisticEntryPrice,
    /** Simulation P&L valued at {@link #realisticEntryPrice}. */
    BigDecimal realisticPnl,
    BigDecimal rrRatio,
    String verdict,
    String priceSource,
    Instant priceTimestamp,
    Instant evaluatedCandleTs,
    String entryType,
    BigDecimal invalidationPrice,
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
                                                      BigDecimal realisticEntryPrice,
                                                      BigDecimal realisticPnl,
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
            decision.entryPrice(),
            decision.stopLoss(),
            decision.takeProfit1(),
            decision.takeProfit2(),
            decision.routingOutcome(),
            decision.routingErrorMessage(),
            decision.executionId(),
            simulationStatus,
            simulationPnl,
            simulationPnl,
            realisticEntryPrice,
            realisticPnl,
            decision.rrRatio(),
            decision.verdict(),
            decision.priceSource(),
            decision.priceTimestamp(),
            decision.evaluatedCandleTs(),
            decision.entryType(),
            decision.invalidationPrice(),
            summary
        );
    }
}
