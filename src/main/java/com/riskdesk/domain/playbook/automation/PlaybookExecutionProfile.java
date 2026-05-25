package com.riskdesk.domain.playbook.automation;

import java.util.Locale;

public enum PlaybookExecutionProfile {
    LEGACY(true, false, false),
    MGC_10M_SCALP_0_5R(true, true, false),
    MGC_10M_NORMAL_1R_BENCHMARK(false, false, true);

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
        return decision != null
            && "MGC".equalsIgnoreCase(decision.instrument())
            && "10m".equalsIgnoreCase(decision.timeframe())
            && "BREAK_RETEST".equalsIgnoreCase(decision.setupType());
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
