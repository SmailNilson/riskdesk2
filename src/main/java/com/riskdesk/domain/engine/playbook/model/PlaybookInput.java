package com.riskdesk.domain.engine.playbook.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Pure domain input for playbook evaluation.
 * Constructed by application-layer services — no dependency on application DTOs.
 */
public record PlaybookInput(
    String swingBias,
    String internalBias,
    BigDecimal swingHigh,
    BigDecimal swingLow,
    BigDecimal lastPrice,
    List<SmcBreak> recentBreaks,
    List<SmcOrderBlock> activeOrderBlocks,
    List<SmcOrderBlock> breakerOrderBlocks,
    List<SmcFvg> activeFairValueGaps,
    List<SmcEqualLevel> equalHighs,
    List<SmcEqualLevel> equalLows,
    Double pocPrice,
    Double valueAreaHigh,
    Double valueAreaLow,
    String deltaFlowBias,
    BigDecimal buyRatio,
    String currentZone,
    String sessionPdZone,
    BigDecimal atr
) {}
