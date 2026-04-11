package com.riskdesk.domain.shared;

/**
 * CME Globex session phases based on Eastern Time.
 * <p>
 * These phases segment the 24h trading day into liquidity windows
 * that Gemini uses for context-aware analysis (e.g., NY_AM has highest
 * volume, ASIAN is typically lower liquidity).
 */
public enum SessionPhase {
    /** 17:00-02:00 ET — CME Globex Asia / overnight session. */
    ASIAN,
    /** 02:00-08:30 ET — London + European open. */
    LONDON,
    /** 08:30-12:00 ET — NY morning, highest volume window. */
    NY_AM,
    /** 12:00-16:00 ET — NY afternoon, lower volume. */
    NY_PM,
    /** 16:00-17:00 ET — Settlement / maintenance window. */
    CLOSE,
    /** Weekend: Friday 17:00 ET through Sunday 17:00 ET. */
    CLOSED
}
