package com.riskdesk.domain.engine.strategy.playbook;

import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An autonomous Playbook signal detected on a closed candle.
 */
public record PlaybookSignal(
        UUID id,
        String instrument,
        String timeframe,
        Instant evaluatedAt,
        String direction,
        int checklistScore,
        String setupType,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal takeProfit1,
        BigDecimal takeProfit2,
        WtxRoutingOutcome routingOutcome,
        String routingErrorMessage
) {
    public PlaybookSignal withRouting(WtxRoutingResult result) {
        if (result == null) {
            return this;
        }
        return new PlaybookSignal(id, instrument, timeframe, evaluatedAt, direction,
                checklistScore, setupType, entryPrice, stopLoss, takeProfit1, takeProfit2,
                result.outcome(), result.errorMessage());
    }
}
