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
    QUANT_AUTO_ARM
}
