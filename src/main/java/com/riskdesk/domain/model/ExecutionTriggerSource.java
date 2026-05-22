package com.riskdesk.domain.model;

public enum ExecutionTriggerSource {
    MANUAL_ARMING,
    AUTO_ARMING,
    RECOVERY_REPLAY,
    /**
     * Auto-armed execution created by the quant 7-gate pipeline (PR #303).
     * No mentor signal review is associated; the execution is created
     * directly from a {@code QuantSnapshot} with score &gt;= configured min.
     */
    QUANT_AUTO_ARM,
    /**
     * Manual order placed by the operator from the QuantGatePanel manual
     * trade ticket. No mentor signal review is associated; the operator
     * supplies entry/SL/TP directly. Independent of auto-arm thresholds —
     * the user takes responsibility for the trade.
     */
    MANUAL_QUANT_PANEL,
    /**
     * Auto-routed execution from the WTX strategy ({@link com.riskdesk.application.service.strategy.WtxExecutionBridge}).
     * Opt-in per instrument via the WTX panel "Auto-IBKR" toggle. No mentor review is involved.
     */
    WTX_AUTO,
    /**
     * Auto-routed execution from deterministic PLAYBOOK automation.
     * Opt-in per instrument/timeframe via the PLAYBOOK panel. No mentor review is involved.
     */
    PLAYBOOK_AUTO
}
