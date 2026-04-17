package com.riskdesk.domain.engine.strategy.model;

/**
 * Session / liquidity context consumed by the
 * {@code SessionTimingAgent}. Strategy-local to keep the engine decoupled from
 * the legacy playbook agent package.
 *
 * <p>All fields optional — {@link #unknown()} marks "no session data". Agents
 * MUST check {@link #isKnown()} and abstain when data is missing rather than
 * treating defaults as "safe".
 *
 * @param phase          session phase name (e.g. {@code "NY_AM"}, {@code "LONDON"},
 *                       {@code "ASIAN"}) from
 *                       {@code com.riskdesk.domain.shared.SessionPhase}. Nullable.
 * @param killZone       true when timestamp falls in an ICT kill zone
 *                       (London 02:00–05:00 ET or NY 08:30–11:00 ET)
 * @param marketOpen     true when the instrument's market is open (respects CME
 *                       weekend / holiday closures)
 * @param maintenanceWindow true when the daily maintenance window is active
 *                       (16:00–18:00 ET for CME standard; 16:00–18:00 ET for FX)
 */
public record SessionInfo(
    String phase,
    boolean killZone,
    boolean marketOpen,
    boolean maintenanceWindow,
    boolean known
) {

    /** 4-arg helper — treats state as known. */
    public SessionInfo(String phase, boolean killZone, boolean marketOpen, boolean maintenanceWindow) {
        this(phase, killZone, marketOpen, maintenanceWindow, true);
    }

    public static SessionInfo unknown() {
        return new SessionInfo(null, false, true, false, false);
    }

    public boolean isKnown() {
        return known;
    }

    /** Low-liquidity if we're in the Asian session or the market isn't fully open. */
    public boolean isLowLiquidity() {
        return "ASIAN".equalsIgnoreCase(phase) || "CLOSE".equalsIgnoreCase(phase) || maintenanceWindow;
    }

    /** Prime-time liquidity — NY AM or London overlap. */
    public boolean isHighLiquidity() {
        return "NY_AM".equalsIgnoreCase(phase) || "LONDON".equalsIgnoreCase(phase);
    }
}
