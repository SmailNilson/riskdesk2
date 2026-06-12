package com.riskdesk.application.dto;

import java.time.Instant;

/** REST view of one CVD-divergence paper trade (UC-OF-CVD-PAPER). */
public record CvdDivergencePaperTradeView(
    Long id,
    String instrument,
    String direction,
    String divergenceType,
    Instant entryTime,
    double entryPrice,
    Instant lastSignalTime,
    String status,
    Instant exitTime,
    Double exitPrice,
    String closeReason,
    Double pnlPoints,
    Double pnlCurrency
) {}
