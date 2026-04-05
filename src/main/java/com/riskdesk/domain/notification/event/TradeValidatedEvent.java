package com.riskdesk.domain.notification.event;

import com.riskdesk.domain.shared.event.DomainEvent;

import java.time.Instant;

/**
 * Published when a Mentor AI review completes with execution eligibility ELIGIBLE
 * and a complete trade plan (Entry / SL / TP).
 */
public record TradeValidatedEvent(
        String instrument,
        String action,
        String timeframe,
        String verdict,
        String technicalQuickAnalysis,
        Double entryPrice,
        Double deepEntryPrice,
        Double stopLoss,
        Double takeProfit,
        Double rewardToRiskRatio,
        Instant timestamp
) implements DomainEvent {
}
