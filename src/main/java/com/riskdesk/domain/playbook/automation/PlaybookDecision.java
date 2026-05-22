package com.riskdesk.domain.playbook.automation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record PlaybookDecision(
    Long id,
    String decisionKey,
    String instrument,
    String timeframe,
    String setupIdentity,
    String setupType,
    String zoneName,
    String direction,
    int checklistScore,
    String verdict,
    BigDecimal entryPrice,
    BigDecimal stopLoss,
    BigDecimal takeProfit1,
    BigDecimal takeProfit2,
    BigDecimal rrRatio,
    BigDecimal riskPercent,
    boolean lateEntry,
    String priceSource,
    Instant priceTimestamp,
    Instant evaluatedCandleTs,
    Instant createdAt,
    PlaybookRoutingOutcome routingOutcome,
    String routingErrorMessage,
    Long executionId
) {
    public PlaybookDecision {
        Objects.requireNonNull(decisionKey, "decisionKey");
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(timeframe, "timeframe");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(evaluatedCandleTs, "evaluatedCandleTs");
    }

    public PlaybookDecision withRouting(PlaybookRoutingOutcome outcome, String errorMessage, Long executionId) {
        return new PlaybookDecision(
            id,
            decisionKey,
            instrument,
            timeframe,
            setupIdentity,
            setupType,
            zoneName,
            direction,
            checklistScore,
            verdict,
            entryPrice,
            stopLoss,
            takeProfit1,
            takeProfit2,
            rrRatio,
            riskPercent,
            lateEntry,
            priceSource,
            priceTimestamp,
            evaluatedCandleTs,
            createdAt,
            outcome,
            errorMessage,
            executionId
        );
    }
}
