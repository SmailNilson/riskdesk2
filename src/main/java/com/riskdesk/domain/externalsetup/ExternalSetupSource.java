package com.riskdesk.domain.externalsetup;

/**
 * Origin of an external setup submission. Currently only Claude wakeup loop pushes setups,
 * but the enum is open for future webhook-style sources (TradingView, custom bots).
 */
public enum ExternalSetupSource {
    /** Setup proposed by the Claude monitoring loop polling RiskDesk endpoints. */
    CLAUDE_WAKEUP
}
