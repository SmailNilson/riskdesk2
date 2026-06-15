package com.riskdesk.domain.marketdata.port;

import com.riskdesk.domain.marketdata.model.SessionCvd;
import com.riskdesk.domain.marketdata.model.TapeSpeed;
import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.model.Instrument;

import java.time.Instant;
import java.util.Optional;

/**
 * Domain port for accessing real-time tick-by-tick trade data aggregations.
 * Infrastructure adapters implement this to provide aggregated order flow data
 * from IBKR tick-by-tick feeds or CLV-based candle estimation as fallback.
 */
public interface TickDataPort {

    /**
     * Returns the current tick aggregation for the given instrument,
     * or empty if no aggregation data is available.
     */
    Optional<TickAggregation> currentAggregation(Instrument instrument);

    /**
     * Returns an aggregation over only the last {@code windowSeconds} of ticks.
     * <p>
     * Used by short-window detectors (absorption, momentum) that need a tight time
     * frame to detect transient events. Implementations without windowing support
     * fall back to {@link #currentAggregation(Instrument)}.
     */
    default Optional<TickAggregation> recentAggregation(Instrument instrument, long windowSeconds) {
        return currentAggregation(instrument);
    }

    /**
     * Like {@link #currentAggregation(Instrument)} but guaranteed not to mutate any trend state —
     * safe to call from a non-scheduler thread (status/diagnostics) without racing the scheduler.
     */
    default Optional<TickAggregation> currentAggregationReadOnly(Instrument instrument) {
        return currentAggregation(instrument);
    }

    /**
     * Returns true if real tick-by-tick data (not CLV estimation) is currently
     * available for this instrument.
     */
    boolean isRealTickDataAvailable(Instrument instrument);

    /**
     * Wall-clock instant of the last <b>classified</b> tick (BUY/SELL) for this instrument, or
     * empty if none. Keyed on classification yield — not raw arrival — so a stream that delivers
     * only UNCLASSIFIED trades reads as stale. Drives the delta-freshness watchdog (L3).
     */
    default Optional<Instant> lastClassifiedAt(Instrument instrument) {
        return Optional.empty();
    }

    /**
     * Timestamp of the last genuine (classified) tick's trade time for this instrument, or empty.
     * Used as the {@code dataTimestamp} of a staleness heartbeat so a quiet/empty window surfaces
     * the last real data time rather than {@code now} (L4).
     */
    default Optional<Instant> lastGenuineWindowEnd(Instrument instrument) {
        return Optional.empty();
    }

    /** Total number of classified (BUY/SELL) ticks received — the gap vs raw ticks = dropped (L3). */
    default long classifiedTicksReceived() {
        return 0L;
    }

    /**
     * Session-anchored CVD for this instrument (RTH anchor inside 09:30–16:00 ET, else
     * Globex-day anchor) — see {@link SessionCvd}. Empty when no tick data exists.
     * Read-only; never mutates aggregation state.
     */
    default Optional<SessionCvd> sessionCvd(Instrument instrument) {
        return Optional.empty();
    }

    /**
     * Speed of tape (prints/sec, contracts/sec) over the trailing {@code windowSeconds}.
     * Empty when no tick data exists. Read-only; never mutates aggregation state.
     */
    default Optional<TapeSpeed> tapeSpeed(Instrument instrument, long windowSeconds) {
        return Optional.empty();
    }

    /**
     * Discards all per-instrument tick state — rolling aggregation window, tick-rule reference
     * price, freshness markers and any derived bar/footprint/big-print builders. Called on a
     * <b>contract rollover</b> so the new contract starts from a clean slate and no aggregation,
     * delta, tick-bar or detector window straddles the old→new contract price gap (which would
     * otherwise fabricate a spurious move the size of the calendar spread). Default no-op for
     * adapters that hold no per-instrument state (e.g. CLV estimation).
     */
    default void purgeInstrument(Instrument instrument) {
        // no-op
    }
}
