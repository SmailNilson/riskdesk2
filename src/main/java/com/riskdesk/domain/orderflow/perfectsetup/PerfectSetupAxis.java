package com.riskdesk.domain.orderflow.perfectsetup;

/**
 * The six order-flow confluence axes a Perfect Setup is scored on. Each axis is
 * evaluated per direction (LONG / SHORT) and contributes one point when it
 * passes.
 */
public enum PerfectSetupAxis {

    /** Multi-event regime: accumulation (LONG) / distribution (SHORT) with confidence. */
    REGIME("Régime Accum/Distrib"),
    /** Hidden institutional interest: a directional iceberg near price. */
    ICEBERG("Iceberg directionnel"),
    /** Dominant absorption pressure favouring the direction at a climax score. */
    ABSORPTION("Absorption dominante"),
    /** Price located at value extreme: lower band / discount (LONG), upper / premium (SHORT). */
    VALUE("Position vs VWAP/BB"),
    /** A liquidity grab (flash-crash bid-collapse) was flushed and reversed. */
    LIQUIDITY_GRAB("Liquidity grab racheté"),
    /** Computed reward-to-risk meets the minimum. */
    RISK_REWARD("Risk:Reward suffisant");

    private final String label;

    PerfectSetupAxis(String label) {
        this.label = label;
    }

    /** Human-readable label surfaced in the UI checklist. */
    public String label() {
        return label;
    }

    /** Outcome of evaluating one axis for one direction. */
    public record Result(PerfectSetupAxis axis, boolean passed, String detail) {
        public Result {
            if (axis == null) throw new IllegalArgumentException("axis is required");
            detail = detail == null ? "" : detail;
        }
    }
}
