package com.riskdesk.domain.quant.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistent state for the Quant 7-Gates evaluator. Immutable record — all
 * mutations return a new instance via the {@code with*} helpers. The history
 * lists are capped at {@link #HISTORY_CAPACITY} entries to mirror the Python
 * reference implementation.
 *
 * <p><b>Trading day reset</b> — when {@link #sessionDate} differs from the
 * current ET calendar day (midnight rollover, matching the Python script),
 * the state is wiped via {@link #reset(LocalDate)} so each session starts
 * with a clean slate.
 */
public record QuantState(
    LocalDate sessionDate,
    Double monitorStartPx,
    List<Double> deltaHistory,
    List<DistEntry> distOnlyHistory,
    List<DistEntry> accuOnlyHistory,
    List<Instant> absBullScans30m
) {
    public static final int HISTORY_CAPACITY = 3;
    public static final int ABS_BULL_WINDOW_MINUTES = 30;

    public QuantState {
        deltaHistory = deltaHistory == null ? List.of() : List.copyOf(deltaHistory);
        distOnlyHistory = distOnlyHistory == null ? List.of() : List.copyOf(distOnlyHistory);
        accuOnlyHistory = accuOnlyHistory == null ? List.of() : List.copyOf(accuOnlyHistory);
        absBullScans30m = absBullScans30m == null ? List.of() : List.copyOf(absBullScans30m);
    }

    /** Returns a fresh state for the given trading date with no history. */
    public static QuantState reset(LocalDate sessionDate) {
        return new QuantState(sessionDate, null, List.of(), List.of(), List.of(), List.of());
    }

    public QuantState withMonitorStartPx(double px) {
        return new QuantState(sessionDate, px, deltaHistory, distOnlyHistory, accuOnlyHistory, absBullScans30m);
    }

    public QuantState appendDelta(double delta) {
        List<Double> next = appendCapped(deltaHistory, delta, HISTORY_CAPACITY);
        return new QuantState(sessionDate, monitorStartPx, next, distOnlyHistory, accuOnlyHistory, absBullScans30m);
    }

    public QuantState appendDistOnly(DistEntry entry) {
        List<DistEntry> next = appendCapped(distOnlyHistory, entry, HISTORY_CAPACITY);
        return new QuantState(sessionDate, monitorStartPx, deltaHistory, next, accuOnlyHistory, absBullScans30m);
    }

    public QuantState appendAccuOnly(DistEntry entry) {
        List<DistEntry> next = appendCapped(accuOnlyHistory, entry, HISTORY_CAPACITY);
        return new QuantState(sessionDate, monitorStartPx, deltaHistory, distOnlyHistory, next, absBullScans30m);
    }

    /**
     * Adds a fresh ABS BULL scan timestamp and prunes anything older than 30 minutes
     * relative to {@code now}. Used by G0 to decide whether the daily regime is bullish.
     */
    public QuantState appendAbsBullAndPrune(Instant scanTs, Instant now) {
        Instant cutoff = now.minusSeconds(ABS_BULL_WINDOW_MINUTES * 60L);
        List<Instant> next = new ArrayList<>(absBullScans30m.size() + 1);
        for (Instant t : absBullScans30m) {
            if (!t.isBefore(cutoff)) next.add(t);
        }
        if (scanTs != null && !scanTs.isBefore(cutoff)) next.add(scanTs);
        return new QuantState(sessionDate, monitorStartPx, deltaHistory, distOnlyHistory, accuOnlyHistory,
            Collections.unmodifiableList(next));
    }

    /** Prune the ABS BULL list without adding a new entry. */
    public QuantState pruneAbsBullScans(Instant now) {
        Instant cutoff = now.minusSeconds(ABS_BULL_WINDOW_MINUTES * 60L);
        List<Instant> next = new ArrayList<>(absBullScans30m.size());
        for (Instant t : absBullScans30m) {
            if (!t.isBefore(cutoff)) next.add(t);
        }
        if (next.size() == absBullScans30m.size()) return this;
        return new QuantState(sessionDate, monitorStartPx, deltaHistory, distOnlyHistory, accuOnlyHistory,
            Collections.unmodifiableList(next));
    }

    private static <T> List<T> appendCapped(List<T> source, T entry, int cap) {
        int start = Math.max(0, source.size() + 1 - cap);
        List<T> next = new ArrayList<>(cap);
        for (int i = start; i < source.size(); i++) next.add(source.get(i));
        next.add(entry);
        return Collections.unmodifiableList(next);
    }
}
