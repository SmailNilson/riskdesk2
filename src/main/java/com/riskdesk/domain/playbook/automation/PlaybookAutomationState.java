package com.riskdesk.domain.playbook.automation;

import java.time.Instant;

public record PlaybookAutomationState(
    String instrument,
    String timeframe,
    int paperThreshold,
    int liveThreshold,
    boolean paperEnabled,
    boolean autoExecutionEnabled,
    int configuredOrderQty,
    String brokerAccountId,
    PlaybookExecutionProfile armedProfile,
    boolean scalpProfileValidated,
    Instant updatedAt
) {
    public static final int DEFAULT_PAPER_THRESHOLD = 4;
    public static final int DEFAULT_LIVE_THRESHOLD = 5;
    public static final int DEFAULT_ORDER_QTY = 1;

    public PlaybookAutomationState {
        if (instrument == null || instrument.isBlank()) {
            throw new IllegalArgumentException("instrument must not be blank");
        }
        if (timeframe == null || timeframe.isBlank()) {
            throw new IllegalArgumentException("timeframe must not be blank");
        }
        paperThreshold = clampScore(paperThreshold, DEFAULT_PAPER_THRESHOLD);
        liveThreshold = clampScore(liveThreshold, DEFAULT_LIVE_THRESHOLD);
        configuredOrderQty = configuredOrderQty <= 0 ? DEFAULT_ORDER_QTY : configuredOrderQty;
        armedProfile = armedProfile == null ? PlaybookExecutionProfile.LEGACY : armedProfile;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public PlaybookAutomationState(String instrument,
                                   String timeframe,
                                   int paperThreshold,
                                   int liveThreshold,
                                   boolean paperEnabled,
                                   boolean autoExecutionEnabled,
                                   int configuredOrderQty,
                                   String brokerAccountId,
                                   Instant updatedAt) {
        this(
            instrument,
            timeframe,
            paperThreshold,
            liveThreshold,
            paperEnabled,
            autoExecutionEnabled,
            configuredOrderQty,
            brokerAccountId,
            PlaybookExecutionProfile.LEGACY,
            false,
            updatedAt
        );
    }

    public static PlaybookAutomationState initial(String instrument, String timeframe) {
        return new PlaybookAutomationState(
            instrument,
            timeframe,
            DEFAULT_PAPER_THRESHOLD,
            DEFAULT_LIVE_THRESHOLD,
            true,
            false,
            DEFAULT_ORDER_QTY,
            null,
            PlaybookExecutionProfile.LEGACY,
            false,
            Instant.now()
        );
    }

    public PlaybookAutomationState withSettings(Boolean paperEnabled,
                                                Boolean autoExecutionEnabled,
                                                Integer configuredOrderQty,
                                                String brokerAccountId,
                                                PlaybookExecutionProfile armedProfile,
                                                Boolean scalpProfileValidated) {
        return new PlaybookAutomationState(
            instrument,
            timeframe,
            paperThreshold,
            liveThreshold,
            paperEnabled == null ? this.paperEnabled : paperEnabled,
            autoExecutionEnabled == null ? this.autoExecutionEnabled : autoExecutionEnabled,
            configuredOrderQty == null ? this.configuredOrderQty : configuredOrderQty,
            brokerAccountId == null ? this.brokerAccountId : blankToNull(brokerAccountId),
            armedProfile == null ? this.armedProfile : armedProfile,
            scalpProfileValidated == null ? this.scalpProfileValidated : scalpProfileValidated,
            Instant.now()
        );
    }

    public PlaybookAutomationState withSettings(Boolean paperEnabled,
                                                Boolean autoExecutionEnabled,
                                                Integer configuredOrderQty,
                                                String brokerAccountId) {
        return withSettings(
            paperEnabled,
            autoExecutionEnabled,
            configuredOrderQty,
            brokerAccountId,
            null,
            null
        );
    }

    private static int clampScore(int score, int fallback) {
        if (score < 0 || score > 7) {
            return fallback;
        }
        return score;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
