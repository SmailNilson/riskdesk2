package com.riskdesk.application.dto.analysis;

import com.riskdesk.domain.analysis.model.DirectionalBias;
import com.riskdesk.domain.analysis.model.LiveVerdict;
import com.riskdesk.domain.analysis.model.TradeScenario;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record LiveVerdictResponse(
    String instrument,
    String timeframe,
    Instant decisionTimestamp,
    int scoringEngineVersion,
    BigDecimal currentPrice,
    BiasView bias,
    List<TradeScenario> scenarios,
    Instant validUntil,
    /**
     * True when {@code validUntil < server now()} at response time. PR #270
     * round-6 review fix: the dashboard polls /latest and would otherwise
     * render a scheduler-stalled, hours-old verdict as if it were current.
     * Frontend banners on this flag; downstream automation can skip armed
     * decisions when expired.
     */
    boolean expired,
    /** Seconds since this verdict's validity window ended; 0 when not expired. */
    long expiredForSeconds
) {

    public static LiveVerdictResponse from(LiveVerdict v) {
        return from(v, Instant.now());
    }

    public static LiveVerdictResponse from(LiveVerdict v, Instant now) {
        DirectionalBias b = v.bias();
        boolean expired = v.validUntil() != null && v.validUntil().isBefore(now);
        long expiredForSec = expired
            ? java.time.Duration.between(v.validUntil(), now).toSeconds()
            : 0L;
        return new LiveVerdictResponse(
            v.instrument().name(),
            v.timeframe().label(),
            v.decisionTimestamp(),
            v.scoringEngineVersion(),
            v.currentPrice(),
            new BiasView(
                b.primary().name(),
                b.confidence(),
                b.structure().value(),
                b.orderFlow().value(),
                b.momentum().value(),
                b.bullishFactors(),
                b.bearishFactors(),
                b.contradictions(),
                b.standAsideReason()
            ),
            v.scenarios(),
            v.validUntil(),
            expired,
            expiredForSec
        );
    }

    public record BiasView(
        String primary,
        int confidence,
        double structureScore,
        double orderFlowScore,
        double momentumScore,
        List<?> bullishFactors,
        List<?> bearishFactors,
        List<?> contradictions,
        String standAsideReason
    ) {}
}
