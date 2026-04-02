package com.riskdesk.domain.marketdata.model;

import java.math.BigDecimal;

/**
 * Represents one FX pair's contribution to the DXY movement.
 * Sorted by |weightedImpact| descending to show the dominant driver first.
 */
public record FxComponentContribution(
    FxPair pair,
    BigDecimal currentRate,
    BigDecimal baselineRate,
    BigDecimal pctChange,
    BigDecimal dxyWeight,
    BigDecimal weightedImpact,
    String impactDirection
) {}
