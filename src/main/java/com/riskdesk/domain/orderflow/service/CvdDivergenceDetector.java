package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.orderflow.model.CvdDivergenceSignal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detects price/CVD swing-pivot divergences on internally-built 1m bars (UC-OF-CVD).
 *
 * <p>Input is a stream of (timestamp, lastPrice, sessionCvd) samples — typically the
 * orchestrator's 5s scheduled pass. The detector buckets samples into 1m bars whose
 * close is the last sample of the minute (a tick-stream-derived 1m close, no candle
 * repository dependency). When a bar closes, rolling swing pivots are confirmed with
 * {@code pivotBars} bars on each side (strictly greater/lower closes), and each newly
 * confirmed pivot is compared to the previous same-side pivot:</p>
 *
 * <ul>
 *   <li>price higher-high + CVD lower-high → {@code BEARISH_DIVERGENCE}</li>
 *   <li>price lower-low + CVD higher-low → {@code BULLISH_DIVERGENCE}</li>
 * </ul>
 *
 * <p>The CVD swing between the two pivots must be at least {@code minCvdSwing}
 * (contracts) to filter flat-CVD noise. Because the session CVD resets at its anchor
 * boundary (RTH open / Globex-day start), all pivot state is cleared whenever the
 * anchor changes — pivots are never compared across a CVD reset.</p>
 *
 * <p>Pure domain service: caller-injected timestamps, no wall clock, no framework.
 * Not thread-safe — drive it from a single scheduler thread.</p>
 */
public class CvdDivergenceDetector {

    private final int pivotBars;
    private final long minCvdSwing;

    /** Closed 1m bars, capped at the pivot-confirmation window (2×pivotBars+1). */
    private final List<Bar> bars = new ArrayList<>();
    private long currentMinute = Long.MIN_VALUE;
    private double currentClose = Double.NaN;
    private long currentCvd;
    private Instant sessionAnchor;

    private Bar lastSwingHigh;
    private Bar lastSwingLow;

    public CvdDivergenceDetector(int pivotBars, long minCvdSwing) {
        if (pivotBars < 1) throw new IllegalArgumentException("pivotBars must be >= 1");
        this.pivotBars = pivotBars;
        this.minCvdSwing = Math.max(0, minCvdSwing);
    }

    /**
     * Feed one sample. Returns a confirmed divergence when the sample rolls a 1m bar
     * whose pivot confirmation produces one; empty otherwise.
     *
     * @param timestamp     sample time (drives 1m bucketing)
     * @param price         last trade price at the sample time
     * @param cvd           session-anchored CVD at the sample time
     * @param sessionAnchor anchor instant of the CVD session — a change clears all
     *                      pivot state so divergences never straddle a CVD reset
     */
    public Optional<CvdDivergenceSignal> onSample(Instant timestamp, double price, long cvd,
                                                  Instant sessionAnchor) {
        if (Double.isNaN(price)) {
            return Optional.empty();
        }
        if (sessionAnchor != null && !sessionAnchor.equals(this.sessionAnchor)) {
            reset();
            this.sessionAnchor = sessionAnchor;
        }

        long minute = Math.floorDiv(timestamp.getEpochSecond(), 60L);
        Optional<CvdDivergenceSignal> signal = Optional.empty();
        if (currentMinute == Long.MIN_VALUE) {
            currentMinute = minute;
        } else if (minute > currentMinute) {
            signal = closeBar();
            currentMinute = minute;
        } else if (minute < currentMinute) {
            return Optional.empty(); // out-of-order sample — ignore
        }
        currentClose = price;
        currentCvd = cvd;
        return signal;
    }

    private void reset() {
        bars.clear();
        currentMinute = Long.MIN_VALUE;
        currentClose = Double.NaN;
        currentCvd = 0;
        lastSwingHigh = null;
        lastSwingLow = null;
    }

    /** Close the in-progress 1m bar and run pivot confirmation on the window. */
    private Optional<CvdDivergenceSignal> closeBar() {
        if (Double.isNaN(currentClose)) {
            return Optional.empty();
        }
        bars.add(new Bar(Instant.ofEpochSecond(currentMinute * 60L), currentClose, currentCvd));
        int window = 2 * pivotBars + 1;
        if (bars.size() > window) {
            bars.remove(0);
        }
        if (bars.size() < window) {
            return Optional.empty();
        }

        // Candidate = the bar with pivotBars confirmed bars on each side.
        Bar candidate = bars.get(pivotBars);
        if (isSwingHigh(candidate)) {
            Optional<CvdDivergenceSignal> signal = checkBearish(candidate);
            lastSwingHigh = candidate;
            if (signal.isPresent()) return signal;
        }
        if (isSwingLow(candidate)) {
            Optional<CvdDivergenceSignal> signal = checkBullish(candidate);
            lastSwingLow = candidate;
            return signal;
        }
        return Optional.empty();
    }

    private boolean isSwingHigh(Bar candidate) {
        for (Bar bar : bars) {
            if (bar != candidate && bar.close() >= candidate.close()) return false;
        }
        return true;
    }

    private boolean isSwingLow(Bar candidate) {
        for (Bar bar : bars) {
            if (bar != candidate && bar.close() <= candidate.close()) return false;
        }
        return true;
    }

    private Optional<CvdDivergenceSignal> checkBearish(Bar newHigh) {
        if (lastSwingHigh == null) return Optional.empty();
        boolean priceHigherHigh = newHigh.close() > lastSwingHigh.close();
        boolean cvdLowerHigh = newHigh.cvd() < lastSwingHigh.cvd();
        boolean swingBigEnough = (lastSwingHigh.cvd() - newHigh.cvd()) >= minCvdSwing;
        if (priceHigherHigh && cvdLowerHigh && swingBigEnough) {
            return Optional.of(new CvdDivergenceSignal(
                CvdDivergenceSignal.BEARISH,
                lastSwingHigh.close(), newHigh.close(),
                lastSwingHigh.cvd(), newHigh.cvd(),
                newHigh.openTime()));
        }
        return Optional.empty();
    }

    private Optional<CvdDivergenceSignal> checkBullish(Bar newLow) {
        if (lastSwingLow == null) return Optional.empty();
        boolean priceLowerLow = newLow.close() < lastSwingLow.close();
        boolean cvdHigherLow = newLow.cvd() > lastSwingLow.cvd();
        boolean swingBigEnough = (newLow.cvd() - lastSwingLow.cvd()) >= minCvdSwing;
        if (priceLowerLow && cvdHigherLow && swingBigEnough) {
            return Optional.of(new CvdDivergenceSignal(
                CvdDivergenceSignal.BULLISH,
                lastSwingLow.close(), newLow.close(),
                lastSwingLow.cvd(), newLow.cvd(),
                newLow.openTime()));
        }
        return Optional.empty();
    }

    /** A closed 1m bar: open time, close price and session CVD at the close. */
    private record Bar(Instant openTime, double close, long cvd) {}
}
