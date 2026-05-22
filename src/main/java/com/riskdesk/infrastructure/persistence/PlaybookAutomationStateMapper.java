package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.playbook.automation.PlaybookAutomationState;
import com.riskdesk.infrastructure.persistence.entity.PlaybookAutomationStateEntity;

final class PlaybookAutomationStateMapper {
    private PlaybookAutomationStateMapper() {
    }

    static PlaybookAutomationState toDomain(PlaybookAutomationStateEntity e) {
        return new PlaybookAutomationState(
            e.getInstrument(),
            e.getTimeframe(),
            e.getPaperThreshold() == null
                ? PlaybookAutomationState.DEFAULT_PAPER_THRESHOLD
                : e.getPaperThreshold(),
            e.getLiveThreshold() == null
                ? PlaybookAutomationState.DEFAULT_LIVE_THRESHOLD
                : e.getLiveThreshold(),
            e.getPaperEnabled() == null || e.getPaperEnabled(),
            Boolean.TRUE.equals(e.getAutoExecutionEnabled()),
            e.getConfiguredOrderQty() == null
                ? PlaybookAutomationState.DEFAULT_ORDER_QTY
                : e.getConfiguredOrderQty(),
            e.getBrokerAccountId(),
            e.getUpdatedAt()
        );
    }

    static void fromDomain(PlaybookAutomationState state, PlaybookAutomationStateEntity e) {
        e.setInstrument(state.instrument());
        e.setTimeframe(state.timeframe());
        e.setPaperThreshold(state.paperThreshold());
        e.setLiveThreshold(state.liveThreshold());
        e.setPaperEnabled(state.paperEnabled());
        e.setAutoExecutionEnabled(state.autoExecutionEnabled());
        e.setConfiguredOrderQty(state.configuredOrderQty());
        e.setBrokerAccountId(state.brokerAccountId());
        e.setUpdatedAt(state.updatedAt());
    }
}
