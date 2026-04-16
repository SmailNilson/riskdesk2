package com.riskdesk.domain.alert.port;

import java.time.Instant;
import java.util.Map;

/**
 * Port for persisting alert evaluator transition state.
 * Allows the evaluator to recover its last-known signal state after a restart,
 * preventing false transitions that would waste Mentor (Gemini) API calls.
 *
 * <p>Two related stores live behind this port:
 * <ul>
 *   <li>Signal state ({@link #loadRecent()}, {@link #save(String, String)},
 *       {@link #remove(String)}) — the last emitted signal per (family, instrument,
 *       timeframe, qualifier).</li>
 *   <li>Candle-close guards ({@link #loadRecentCandleGuards()},
 *       {@link #saveCandleGuard(String, Instant)},
 *       {@link #removeCandleGuard(String)}) — PR-7: the last candle timestamp on
 *       which each signal fired. Persisting this prevents the same alert from
 *       firing twice on the same candle after a restart.</li>
 * </ul>
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

    // ── PR-7 · Candle-close guards ──────────────────────────────────────

    /**
     * Loads the last-fired candle timestamp for each recently-seen signal.
     * Only entries updated within a safety window are returned (same 12h
     * bound as {@link #loadRecent()} to stay consistent on weekends).
     *
     * @return map of serialized candle-guard key → last fired candle timestamp
     */
    default Map<String, Instant> loadRecentCandleGuards() {
        return Map.of();
    }

    /**
     * Upserts a candle-guard entry. Called after an alert is admitted on a
     * specific candle so the guard survives restarts.
     *
     * @param evalKey          serialized candle-guard key (e.g. "rsi:MCL:1h:candle")
     * @param candleTimestamp  the timestamp of the candle on which the alert fired
     */
    default void saveCandleGuard(String evalKey, Instant candleTimestamp) {
        // no-op by default; implementations persisting signal state should override
    }

    /**
     * Removes a candle-guard entry. Called on rollover cleanup alongside
     * {@link #remove(String)} to keep persistent and in-memory state in sync.
     *
     * @param evalKey serialized candle-guard key
     */
    default void removeCandleGuard(String evalKey) {
        // no-op by default; implementations persisting signal state should override
    }
}
