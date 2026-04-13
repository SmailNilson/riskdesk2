package com.riskdesk.domain.engine.playbook.model;

import java.math.BigDecimal;

public record SetupCandidate(
    SetupType type,
    String zoneName,
    BigDecimal zoneHigh,
    BigDecimal zoneLow,
    BigDecimal zoneMid,
    double distanceFromPrice,
    boolean priceInZone,
    boolean reactionVisible,
    boolean orderFlowConfirms,
    double rrRatio,
    int checklistScore
) {}
