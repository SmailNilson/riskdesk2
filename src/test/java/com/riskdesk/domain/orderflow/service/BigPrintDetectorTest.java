package com.riskdesk.domain.orderflow.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-domain tests for {@link BigPrintDetector} with FIXED instants.
 */
class BigPrintDetectorTest {

    private static final Instant BASE = Instant.parse("2026-06-10T14:00:00Z");

    @Test
    void floorApplies_whenDistributionIsEmpty() {
        var detector = new BigPrintDetector(0.99, 10, 30);
        // No distribution yet → only the absolute floor (10 contracts) governs.
        assertTrue(detector.onPrint(21000.0, 12, "BUY", BASE).isPresent());
        assertFalse(detector.onPrint(21000.0, 5, "SELL", BASE.plusSeconds(1)).isPresent());
    }

    @Test
    void p99Threshold_flagsOnlyTheTopOfTheDistribution() {
        var detector = new BigPrintDetector(0.99, 10, 30);
        // Seed sizes 1..100 (one each): p99 size = 99.
        for (int s = 1; s <= 100; s++) {
            detector.onPrint(21000.0, s, "BUY", BASE.plusSeconds(s));
        }
        var flagged = detector.onPrint(21001.0, 99, "BUY", BASE.plusSeconds(200));
        assertTrue(flagged.isPresent(), "size 99 >= p99 (99) must be flagged");
        assertEquals(0.99, flagged.get().percentile(), 0.001);

        var notFlagged = detector.onPrint(21001.0, 50, "BUY", BASE.plusSeconds(201));
        assertFalse(notFlagged.isPresent(), "median-sized print must not be flagged");
    }

    @Test
    void thresholdJudgedAgainstPriorDistribution_excludingThePrintItself() {
        var detector = new BigPrintDetector(0.99, 10, 30);
        // 50 one-lot prints, then one 500-lot sweep: judged vs the prior all-1-lot
        // distribution it is clearly outsized — its own size must not dilute the threshold.
        for (int i = 0; i < 50; i++) {
            detector.onPrint(21000.0, 1, "SELL", BASE.plusSeconds(i));
        }
        var flagged = detector.onPrint(21000.0, 500, "BUY", BASE.plusSeconds(60));
        assertTrue(flagged.isPresent());
        assertEquals(1.0, flagged.get().percentile(), 1e-9);
        assertEquals("BUY", flagged.get().side());
        assertEquals(500, flagged.get().size());
    }

    @Test
    void rollingWindow_evictsOldPrintsFromTheDistribution() {
        var detector = new BigPrintDetector(0.99, 10, 30);
        // Seed a heavy distribution (p99 way above 15)...
        for (int i = 0; i < 100; i++) {
            detector.onPrint(21000.0, 100, "BUY", BASE.plusSeconds(i));
        }
        assertFalse(detector.onPrint(21000.0, 15, "BUY", BASE.plusSeconds(120)).isPresent(),
            "15 lots is small vs a 100-lot distribution");
        // ...35 minutes later every seeded print has rolled off → floor (10) governs again.
        Instant later = BASE.plusSeconds(35 * 60);
        assertTrue(detector.onPrint(21000.0, 15, "BUY", later).isPresent(),
            "after eviction the floor applies and 15 >= 10 is flagged");
    }

    @Test
    void bigPrintDelta5m_sumsSignedFlaggedPrintsAndExpires() {
        var detector = new BigPrintDetector(0.99, 10, 30);
        detector.onPrint(21000.0, 50, "BUY", BASE);                  // +50 (flagged: empty dist, floor)
        detector.onPrint(20999.0, 60, "SELL", BASE.plusSeconds(30)); // -60 (flagged: ≥ p99 of {50})
        detector.onPrint(21000.0, 2, "BUY", BASE.plusSeconds(31));   // not flagged → not counted

        assertEquals(-10, detector.bigPrintDelta5m(BASE.plusSeconds(60)));
        // 6+ minutes after the prints, both have left the 5-min delta window.
        assertEquals(0, detector.bigPrintDelta5m(BASE.plusSeconds(400)));
    }

    @Test
    void zeroOrNegativeSizes_areIgnored() {
        var detector = new BigPrintDetector(0.99, 10, 30);
        assertFalse(detector.onPrint(21000.0, 0, "BUY", BASE).isPresent());
        assertEquals(0, detector.bigPrintDelta5m(BASE));
    }
}
