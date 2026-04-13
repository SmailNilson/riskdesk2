package com.riskdesk.domain.engine.playbook.model;

import java.math.BigDecimal;

/**
 * Domain-level structure break view for playbook evaluation.
 */
public record SmcBreak(
    String type,
    String trend,
    BigDecimal level,
    String structureLevel,
    Double breakConfidenceScore
) {}
