package com.riskdesk.domain.engine.playbook.model;

import java.math.BigDecimal;

/**
 * Domain-level order block view for playbook evaluation.
 * Decoupled from application DTO to respect hexagonal architecture.
 */
public record SmcOrderBlock(
    String type,
    String status,
    BigDecimal high,
    BigDecimal low,
    BigDecimal mid,
    String originalType
) {}
