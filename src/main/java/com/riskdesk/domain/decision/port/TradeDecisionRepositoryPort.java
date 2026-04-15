package com.riskdesk.domain.decision.port;

import com.riskdesk.domain.decision.model.TradeDecision;

import java.util.List;
import java.util.Optional;

/**
 * Domain persistence port for {@link TradeDecision}. Implemented in infrastructure by
 * a JPA-backed adapter. Keeps the domain free of JPA annotations.
 */
public interface TradeDecisionRepositoryPort {

    /** Persist or update. Returns the decision with its generated id set. */
    TradeDecision save(TradeDecision decision);

    Optional<TradeDecision> findById(Long id);

    /** Most recent decisions, newest first. */
    List<TradeDecision> findRecent(int limit);

    /** Recent decisions filtered by instrument. */
    List<TradeDecision> findRecentByInstrument(String instrument, int limit);

    /**
     * All revisions of a decision thread (same instrument/timeframe/direction/zone),
     * oldest first. Used to display revision history in the UI.
     */
    List<TradeDecision> findThread(String instrument, String timeframe,
                                    String direction, String zoneName);
}
