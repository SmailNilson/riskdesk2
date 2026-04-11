package com.riskdesk.domain.orderflow.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.DepthMetrics;
import com.riskdesk.domain.orderflow.model.WallEvent;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Domain port for accessing real-time market depth (Level 2 order book) data.
 * Implemented by infrastructure adapters that maintain an in-memory order book
 * from IBKR reqMarketDepth() callbacks.
 */
public interface MarketDepthPort {

    /**
     * Returns current depth metrics for the instrument, or empty if depth data is unavailable.
     * This is a lightweight on-demand read — no object allocation on the hot path.
     */
    Optional<DepthMetrics> currentDepth(Instrument instrument);

    /**
     * Returns recent wall events (appearance/disappearance of large orders)
     * within the specified lookback duration. Used by the Spoofing Detector.
     *
     * @param instrument the instrument to query
     * @param lookback   maximum age of events to return (e.g., Duration.ofSeconds(30))
     */
    List<WallEvent> recentWallEvents(Instrument instrument, Duration lookback);

    /**
     * Returns true if depth data is currently available for this instrument.
     */
    boolean isDepthAvailable(Instrument instrument);
}
