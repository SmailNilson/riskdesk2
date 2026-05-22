package com.riskdesk.domain.playbook.automation.port;

import com.riskdesk.domain.playbook.automation.PlaybookAutomationState;

import java.util.Optional;

public interface PlaybookAutomationStatePort {
    Optional<PlaybookAutomationState> load(String instrument, String timeframe);

    PlaybookAutomationState save(PlaybookAutomationState state);
}
