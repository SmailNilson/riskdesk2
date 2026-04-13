package com.riskdesk.domain.engine.playbook.model;

import java.math.BigDecimal;

/**
 * Domain-level equal level (EQH/EQL) view for playbook evaluation.
 */
public record SmcEqualLevel(
    String type,
    BigDecimal price,
    int touchCount
) {}
