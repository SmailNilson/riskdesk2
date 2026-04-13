package com.riskdesk.domain.engine.playbook.model;

public record FilterResult(
    boolean biasAligned,
    String swingBias,
    Direction tradeDirection,
    boolean structureClean,
    int validBreaks,
    int fakeBreaks,
    int totalBreaks,
    double sizeMultiplier,
    boolean vaPositionOk,
    VaPosition vaPosition,
    boolean allFiltersPass
) {}
