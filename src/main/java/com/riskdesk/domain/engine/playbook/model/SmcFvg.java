package com.riskdesk.domain.engine.playbook.model;

import java.math.BigDecimal;

/**
 * Domain-level fair value gap view for playbook evaluation.
 */
public record SmcFvg(
    String bias,
    BigDecimal top,
    BigDecimal bottom
) {}
