package com.riskdesk.domain.engine.playbook.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Pure domain input for playbook evaluation.
 * Constructed by application-layer services — no dependency on application DTOs.
 *
 * <p><b>Pivot staleness note:</b> {@code swingHigh} and {@code swingLow} are
 * <i>confirmed</i> pivots propagated from {@link com.riskdesk.domain.engine.smc.SmcStructureEngine.Pivot},
 * which carries an inherent confirmation lag equal to the engine's swing lookback
 * (default 50 bars). On 1h candles a "current" swing pivot reflects price action
 * roughly two days in the past. Treat these levels as structural anchors, not as
 * real-time prices.
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
