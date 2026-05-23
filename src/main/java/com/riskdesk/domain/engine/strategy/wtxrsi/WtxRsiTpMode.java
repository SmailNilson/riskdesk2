package com.riskdesk.domain.engine.strategy.wtxrsi;

/**
 * Take-profit mode.
 * - REVERSAL: position is closed when the opposite WT signal fires
 *   (no static TP price is set on the broker side)
 * - R_MULTIPLE: TP = entry ± (tpRMultiple × initial risk) — fixed price
 */
public enum WtxRsiTpMode {
    REVERSAL,
    R_MULTIPLE
}
