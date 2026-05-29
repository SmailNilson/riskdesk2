package com.riskdesk.application.service;

import com.riskdesk.domain.model.Instrument;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transition-style de-duplication gate for batch detectors that re-scan a rolling
 * window every cycle (e.g. Iceberg / Spoofing over the last 30-60s of wall events).
 * <p>
 * Without this, the same iceberg/spoof pattern would be re-emitted on every scan
 * (potentially dozens of times while it remains inside the lookback window),
 * flooding {@code /topic/iceberg} / {@code /topic/spoofing} and the event tables.
 * The gate keeps the alert philosophy consistent with the rest of the system:
 * <b>fire on change, not on persistence</b>.
 * <p>
 * Each signal is reduced to a stable {@code key} (e.g. side + rounded price level).
 * A key is allowed through at most once per {@code cooldownSeconds}. Stale keys are
 * pruned lazily on each call so the map cannot grow unbounded.
 * <p>
 * Pure and thread-safe — no Spring, no I/O. One instance per detector family.
 */
public final class RecentSignalGate {

    private final Map<Instrument, Map<String, Instant>> lastEmit = new ConcurrentHashMap<>();

    /**
     * @return {@code true} if this (instrument, key) has not been emitted within the
     *         last {@code cooldownSeconds}; records {@code now} as the new emit time
     *         and prunes stale entries before returning. {@code false} otherwise.
     */
    public boolean shouldEmit(Instrument instrument, String key, Instant now, long cooldownSeconds) {
        Map<String, Instant> perInstrument = lastEmit.computeIfAbsent(instrument, k -> new ConcurrentHashMap<>());
        Instant last = perInstrument.get(key);
        if (last != null && Duration.between(last, now).getSeconds() < cooldownSeconds) {
            return false;
        }
        perInstrument.put(key, now);

        // Prune entries older than 2× the cooldown (min 120s) so the map stays bounded.
        long pruneAgeSeconds = Math.max(cooldownSeconds * 2, 120);
        perInstrument.entrySet().removeIf(e -> Duration.between(e.getValue(), now).getSeconds() > pruneAgeSeconds);
        return true;
    }

    /** Clears all recorded emit times (e.g. on feed reconnection). */
    public void reset() {
        lastEmit.clear();
    }
}
