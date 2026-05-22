package com.riskdesk.domain.playbook.automation;

public final class PlaybookRoutingPolicy {

    public PlaybookRoutingDecision evaluate(PlaybookDecision decision, PlaybookAutomationState state) {
        if (decision == null || state == null || !hasCompletePlan(decision)) {
            return PlaybookRoutingDecision.skip(PlaybookRoutingOutcome.SKIPPED_NO_PLAN, "missing entry/SL/TP");
        }
        if (decision.checklistScore() < state.paperThreshold()) {
            return PlaybookRoutingDecision.skip(
                PlaybookRoutingOutcome.SKIPPED_BELOW_PAPER_THRESHOLD,
                "score " + decision.checklistScore() + "/7 below paper threshold " + state.paperThreshold() + "/7");
        }
        if (!state.paperEnabled()) {
            return PlaybookRoutingDecision.skip(PlaybookRoutingOutcome.SKIPPED_AUTO_OFF, "paper simulation disabled");
        }
        if (!state.autoExecutionEnabled()) {
            return PlaybookRoutingDecision.paperOnly(PlaybookRoutingOutcome.PAPER_ONLY, null);
        }
        if (decision.checklistScore() < state.liveThreshold()) {
            return PlaybookRoutingDecision.paperOnly(
                PlaybookRoutingOutcome.SKIPPED_BELOW_LIVE_THRESHOLD,
                "score " + decision.checklistScore() + "/7 below live threshold " + state.liveThreshold() + "/7");
        }
        if (decision.lateEntry()) {
            return PlaybookRoutingDecision.paperOnly(PlaybookRoutingOutcome.SKIPPED_LATE_ENTRY, "late entry");
        }
        if (state.configuredOrderQty() <= 0) {
            return PlaybookRoutingDecision.paperOnly(PlaybookRoutingOutcome.SKIPPED_NO_QTY, "quantity must be positive");
        }
        if (state.brokerAccountId() == null || state.brokerAccountId().isBlank()) {
            return PlaybookRoutingDecision.paperOnly(PlaybookRoutingOutcome.SKIPPED_NO_ACCOUNT, "broker account missing");
        }
        return PlaybookRoutingDecision.liveAllowed();
    }

    private static boolean hasCompletePlan(PlaybookDecision decision) {
        return decision.entryPrice() != null
            && decision.stopLoss() != null
            && decision.takeProfit1() != null;
    }
}
