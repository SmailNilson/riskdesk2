package com.riskdesk.domain.alert.port;

import java.util.Map;

/**
 * Port for persisting alert evaluator transition state.
 * Allows the evaluator to recover its last-known signal state after a restart,
 * preventing false transitions that would waste Mentor (Gemini) API calls.
 */
public interface AlertStateStore {

    /**
     * Loads all recent evaluator states (only states updated within a safety window,
     * e.g. 12 hours, to avoid stale weekend data blocking real transitions).
     *
     * @return map of serialized eval-key → last known signal value
     */
    Map<String, String> loadRecent();

    /**
     * Upserts a single evaluator state entry.
     *
     * @param evalKey  serialized eval-key (e.g. "RSI:MCL:1h:null"), max 255 chars
     * @param signal   last known signal value (e.g. "OVERSOLD", "BULLISH_CROSS")
     */
    void save(String evalKey, String signal);

    /**
     * Removes a state entry (when signal transitions to null).
     *
     * @param evalKey serialized eval-key
     */
    void remove(String evalKey);
}
