package com.riskdesk.application.dto;

import com.riskdesk.domain.playbook.automation.PlaybookAutomationState;

import java.time.Instant;

public record PlaybookAutomationStateView(
    String instrument,
    String timeframe,
    int paperThreshold,
    int liveThreshold,
    boolean paperEnabled,
    boolean autoIbkrEnabled,
    int quantity,
    String brokerAccountId,
    String armedProfile,
    boolean scalpProfileValidated,
    Instant updatedAt
) {
    public static PlaybookAutomationStateView from(PlaybookAutomationState state) {
        return new PlaybookAutomationStateView(
            state.instrument(),
            state.timeframe(),
            state.paperThreshold(),
            state.liveThreshold(),
            state.paperEnabled(),
            state.autoExecutionEnabled(),
            state.configuredOrderQty(),
            state.brokerAccountId(),
            state.armedProfile().name(),
            state.scalpProfileValidated(),
            state.updatedAt()
        );
    }
}
