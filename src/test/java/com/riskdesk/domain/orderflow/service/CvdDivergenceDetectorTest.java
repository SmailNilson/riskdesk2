package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.orderflow.model.CvdDivergenceSignal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-domain tests for {@link CvdDivergenceDetector} with FIXED instants.
 * <p>
 * pivotBars=2 keeps the sequences compact: a pivot needs 2 strictly lower/higher
 * closes on each side (window of 5 closed 1m bars), and a bar closes when the first
 * sample of a later minute arrives.
 */
class CvdDivergenceDetectorTest {

    private static final Instant BASE = Instant.parse("2026-06-10T14:00:00Z");

    /** Feed one sample per minute: (price, cvd) at BASE + index minutes. */
    private static List<CvdDivergenceSignal> feed(CvdDivergenceDetector detector,
                                                  double[][] priceCvdPerMinute) {
        return feed(detector, priceCvdPerMinute, BASE);
    }

    private static List<CvdDivergenceSignal> feed(CvdDivergenceDetector detector,
                                                  double[][] priceCvdPerMinute, Instant anchor) {
        List<CvdDivergenceSignal> signals = new ArrayList<>();
        for (int m = 0; m < priceCvdPerMinute.length; m++) {
            Instant ts = BASE.plusSeconds(m * 60L);
            detector.onSample(ts, priceCvdPerMinute[m][0], (long) priceCvdPerMinute[m][1], anchor)
                .ifPresent(signals::add);
        }
        return signals;
    }

    @Test
    void bearishDivergence_priceHigherHighWithCvdLowerHigh() {
        var detector = new CvdDivergenceDetector(2, 10);
        // Swing high 1 at minute 2 (105, cvd 1000); swing high 2 at minute 6 (107, cvd 900).
        var signals = feed(detector, new double[][]{
            {100, 500}, {101, 800}, {105, 1000}, {101, 950}, {100, 900},
            {101, 920}, {107, 900}, {101, 850}, {100, 800}, {100, 800},
        });
        assertEquals(1, signals.size());
        var s = signals.get(0);
        assertEquals(CvdDivergenceSignal.BEARISH, s.type());
        assertEquals(105.0, s.prevPivotPrice(), 1e-9);
        assertEquals(107.0, s.newPivotPrice(), 1e-9);
        assertEquals(1000L, s.prevPivotCvd());
        assertEquals(900L, s.newPivotCvd());
        assertEquals(BASE.plusSeconds(6 * 60L), s.pivotTimestamp(), "pivot = the minute-6 bar");
    }

    @Test
    void bullishDivergence_priceLowerLowWithCvdHigherLow() {
        var detector = new CvdDivergenceDetector(2, 10);
        // Swing low 1 at minute 2 (95, cvd 300); swing low 2 at minute 6 (93, cvd 400).
        var signals = feed(detector, new double[][]{
            {100, 500}, {99, 400}, {95, 300}, {99, 350}, {100, 380},
            {99, 360}, {93, 400}, {99, 420}, {100, 450}, {100, 450},
        });
        assertEquals(1, signals.size());
        var s = signals.get(0);
        assertEquals(CvdDivergenceSignal.BULLISH, s.type());
        assertEquals(95.0, s.prevPivotPrice(), 1e-9);
        assertEquals(93.0, s.newPivotPrice(), 1e-9);
        assertEquals(300L, s.prevPivotCvd());
        assertEquals(400L, s.newPivotCvd());
    }

    @Test
    void noDivergence_whenPriceAndCvdConfirmEachOther() {
        var detector = new CvdDivergenceDetector(2, 10);
        // Higher price high WITH higher CVD high → trend confirmation, not divergence.
        var signals = feed(detector, new double[][]{
            {100, 500}, {101, 800}, {105, 1000}, {101, 950}, {100, 900},
            {101, 920}, {107, 1100}, {101, 1050}, {100, 1000}, {100, 1000},
        });
        assertTrue(signals.isEmpty());
    }

    @Test
    void noDivergence_whenCvdSwingBelowMinimum() {
        var detector = new CvdDivergenceDetector(2, 10);
        // CVD lower-high but only by 5 (< minCvdSwing 10) → filtered as noise.
        var signals = feed(detector, new double[][]{
            {100, 500}, {101, 800}, {105, 1000}, {101, 950}, {100, 900},
            {101, 920}, {107, 995}, {101, 940}, {100, 900}, {100, 900},
        });
        assertTrue(signals.isEmpty());
    }

    @Test
    void anchorChange_clearsPivotStateAcrossCvdReset() {
        var detector = new CvdDivergenceDetector(2, 10);
        // Same bearish sequence, but the session anchor flips mid-way (CVD reset):
        // pivot 1 must be forgotten, so no divergence may be emitted.
        List<CvdDivergenceSignal> signals = new ArrayList<>();
        double[][] sequence = {
            {100, 500}, {101, 800}, {105, 1000}, {101, 950}, {100, 900},
            {101, 920}, {107, 900}, {101, 850}, {100, 800}, {100, 800},
        };
        Instant anchor1 = Instant.parse("2026-06-09T21:00:00Z");
        Instant anchor2 = Instant.parse("2026-06-10T13:30:00Z");
        for (int m = 0; m < sequence.length; m++) {
            Instant anchor = m < 5 ? anchor1 : anchor2;
            detector.onSample(BASE.plusSeconds(m * 60L), sequence[m][0], (long) sequence[m][1], anchor)
                .ifPresent(signals::add);
        }
        assertTrue(signals.isEmpty(), "pivots must never be compared across a CVD session reset");
    }

    @Test
    void outOfOrderSamples_areIgnored() {
        var detector = new CvdDivergenceDetector(2, 10);
        detector.onSample(BASE.plusSeconds(120), 100.0, 500, BASE);
        // A sample from an earlier minute must not corrupt the bar stream.
        var signal = detector.onSample(BASE, 999.0, 9999, BASE);
        assertTrue(signal.isEmpty());
    }

    @Test
    void noSignalBeforeTwoConfirmedSameSidePivots() {
        var detector = new CvdDivergenceDetector(2, 10);
        // Only one swing high ever confirms — divergence needs a previous pivot to compare.
        var signals = feed(detector, new double[][]{
            {100, 500}, {101, 800}, {105, 1000}, {101, 950}, {100, 900}, {100, 880},
        });
        assertTrue(signals.isEmpty());
    }
}
