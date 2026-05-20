package com.riskdesk.domain.quant.setup;

/**
 * Lifecycle phase of a {@link SetupRecommendation}.
 *
 * <pre>
 *   DETECTED → CONFIRMED → TRIGGERED → ACTIVE → CLOSED
 *                    ↓                              ↑
 *               INVALIDATED ─────────────────────────
 * </pre>
 */
public enum SetupPhase {
    /** Gates passed; awaiting candle confirmation. */
    DETECTED,
    /** Price action confirms entry zone. */
    CONFIRMED,
    /** Entry order conditions met. */
    TRIGGERED,
    /** Trade is live (position open). */
    ACTIVE,
    /** Setup resolved normally (TP hit or manual close). */
    CLOSED,
    /** Setup no longer valid (SL breach, regime change, timeout). */
    INVALIDATED;

    public boolean isTerminal() {
        return this == CLOSED || this == INVALIDATED;
    }
}
