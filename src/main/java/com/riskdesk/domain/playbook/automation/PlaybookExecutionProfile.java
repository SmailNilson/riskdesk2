package com.riskdesk.domain.playbook.automation;

import java.util.Locale;

public enum PlaybookExecutionProfile {
    LEGACY(true, false, false),
    MGC_10M_SCALP_0_5R(true, true, false),
    MGC_10M_NORMAL_1R_BENCHMARK(false, false, true),
    /**
     * Confirmation-entry profile for MNQ 10m (see {@link ConfirmationEntryPlanner}):
     * STOP entry at the zone exit, zone-broken invalidation, ATR(1.5/2.25) brackets,
     * direction-specific session gates. Live-executable: routes a real IBKR STOP entry
     * when Auto-IBKR is armed on the panel. Backtest provenance in PR #450 /
     * docs/AI_HANDOFF.md — forward (paper) validation is still in progress, so arming
     * Auto-IBKR is the operator's call.
     */
    MNQ_10M_CONFIRMATION(true, false, false);

    private final boolean executable;
    private final boolean manualValidationRequired;
    private final boolean benchmarkOnly;

    PlaybookExecutionProfile(boolean executable,
                             boolean manualValidationRequired,
                             boolean benchmarkOnly) {
        this.executable = executable;
        this.manualValidationRequired = manualValidationRequired;
        this.benchmarkOnly = benchmarkOnly;
    }

    public boolean executable() {
        return executable;
    }

    public boolean manualValidationRequired() {
        return manualValidationRequired;
    }

    public boolean benchmarkOnly() {
        return benchmarkOnly;
    }

    public boolean supports(PlaybookDecision decision) {
        if (this == LEGACY) {
            return true;
        }
        if (decision == null) {
            return false;
        }
        if (!supportsScope(decision.instrument(), decision.timeframe())) {
            return false;
        }
        return switch (this) {
            case MGC_10M_SCALP_0_5R, MGC_10M_NORMAL_1R_BENCHMARK ->
                "BREAK_RETEST".equalsIgnoreCase(decision.setupType());
            default -> true;
        };
    }

    /** Whether this profile may be armed on the given instrument/timeframe pair. */
    public boolean supportsScope(String instrument, String timeframe) {
        return switch (this) {
            case LEGACY -> true;
            case MGC_10M_SCALP_0_5R, MGC_10M_NORMAL_1R_BENCHMARK ->
                "MGC".equalsIgnoreCase(instrument) && "10m".equalsIgnoreCase(timeframe);
            case MNQ_10M_CONFIRMATION ->
                "MNQ".equalsIgnoreCase(instrument) && "10m".equalsIgnoreCase(timeframe);
        };
    }

    public static PlaybookExecutionProfile parseOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return LEGACY;
        }
        try {
            return PlaybookExecutionProfile.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return LEGACY;
        }
    }
}
