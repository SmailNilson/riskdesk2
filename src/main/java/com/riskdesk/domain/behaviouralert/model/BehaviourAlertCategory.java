package com.riskdesk.domain.behaviouralert.model;

/**
 * Categories for behaviour-based alerts.
 * Open for extension: add new values as new rule types are introduced.
 */
public enum BehaviourAlertCategory {
    EMA_PROXIMITY,          // Price approaching EMA50 or EMA200
    SUPPORT_RESISTANCE,     // Price touching a known S/R level (EQH, EQL, strong/weak pivots)
    // CHAIKIN_DIVERGENCE,  // (Phase 2)
    // MACD_BEHAVIOUR,      // (Phase 2)
    // SMC_CONTEXT          // (Phase 2)
}
