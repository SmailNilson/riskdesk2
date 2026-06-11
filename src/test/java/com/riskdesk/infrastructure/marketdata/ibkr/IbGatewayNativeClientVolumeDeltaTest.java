package com.riskdesk.infrastructure.marketdata.ibkr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Per-bar traded volume is derived as deltas of IBKR's session-cumulative VOLUME tick.
 * The first reading after (re)subscribe is a baseline only — attributing the whole
 * session-to-date figure to one bar would over-count by hours of volume — and a counter
 * that goes backwards marks the Globex session reset.
 */
class IbGatewayNativeClientVolumeDeltaTest {

    @Test
    void firstTickAfterSubscribeIsBaselineOnly() {
        assertEquals(0, IbGatewayNativeClient.volumeDelta(-1, 1_500_000));
    }

    @Test
    void monotonicIncreaseYieldsTheIncrement() {
        assertEquals(480, IbGatewayNativeClient.volumeDelta(1_500_000, 1_500_480));
    }

    @Test
    void unchangedCumulativeYieldsZero() {
        assertEquals(0, IbGatewayNativeClient.volumeDelta(1_500_000, 1_500_000));
    }

    @Test
    void sessionReset_newReadingIsTheVolumeSinceReset() {
        // Globex re-open: counter restarts near zero — the new figure is all new volume.
        assertEquals(37, IbGatewayNativeClient.volumeDelta(1_500_000, 37));
    }

    @Test
    void negativeReadingYieldsZero() {
        assertEquals(0, IbGatewayNativeClient.volumeDelta(1_500_000, -5));
    }
}
