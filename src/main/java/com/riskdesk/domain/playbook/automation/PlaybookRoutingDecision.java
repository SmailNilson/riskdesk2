package com.riskdesk.domain.playbook.automation;

public record PlaybookRoutingDecision(
    boolean paperSimulationAllowed,
    boolean liveRoutingAllowed,
    PlaybookRoutingOutcome outcome,
    String reason
) {
    public static PlaybookRoutingDecision paperOnly(PlaybookRoutingOutcome outcome, String reason) {
        return new PlaybookRoutingDecision(true, false, outcome, reason);
    }

    public static PlaybookRoutingDecision skip(PlaybookRoutingOutcome outcome, String reason) {
        return new PlaybookRoutingDecision(false, false, outcome, reason);
    }

    public static PlaybookRoutingDecision liveAllowed() {
        return new PlaybookRoutingDecision(true, true, null, null);
    }
}
