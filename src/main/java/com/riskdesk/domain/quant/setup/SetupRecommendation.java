package com.riskdesk.domain.quant.setup;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root for a scalp / day-trading setup recommendation.
 * Immutable — state transitions produce new instances via {@code withPhase}.
 */
public record SetupRecommendation(
    UUID id,
    Instrument instrument,
    SetupTemplate template,
    SetupStyle style,
    SetupPhase phase,
    MarketRegime regime,
    Direction direction,
    double finalScore,
    BigDecimal entryPrice,
    BigDecimal slPrice,
    BigDecimal tp1Price,
    BigDecimal tp2Price,
    double rrRatio,
    String playbookId,
    List<GateCheckResult> gateResults,
    Instant detectedAt,
    Instant updatedAt
) {
    public SetupRecommendation {
        gateResults = gateResults == null ? List.of() : List.copyOf(gateResults);
        if (detectedAt == null) detectedAt = Instant.now();
        if (updatedAt == null) updatedAt = detectedAt;
    }

    public SetupRecommendation withPhase(SetupPhase newPhase, Instant now) {
        return new SetupRecommendation(id, instrument, template, style, newPhase, regime, direction,
            finalScore, entryPrice, slPrice, tp1Price, tp2Price, rrRatio, playbookId,
            gateResults, detectedAt, now);
    }
}
