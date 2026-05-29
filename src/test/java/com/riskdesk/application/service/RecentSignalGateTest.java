package com.riskdesk.application.service;

import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RecentSignalGateTest {

    private final Instant t0 = Instant.parse("2026-05-29T12:00:00Z");

    @Test
    void firstEmitOfAKeyIsAllowed() {
        RecentSignalGate gate = new RecentSignalGate();
        assertThat(gate.shouldEmit(Instrument.MNQ, "ICE|BID|80000", t0, 60)).isTrue();
    }

    @Test
    void sameKeyWithinCooldownIsSuppressed() {
        RecentSignalGate gate = new RecentSignalGate();
        gate.shouldEmit(Instrument.MNQ, "ICE|BID|80000", t0, 60);

        // 30s later — still inside the 60s cooldown
        assertThat(gate.shouldEmit(Instrument.MNQ, "ICE|BID|80000", t0.plusSeconds(30), 60)).isFalse();
    }

    @Test
    void sameKeyAfterCooldownIsAllowedAgain() {
        RecentSignalGate gate = new RecentSignalGate();
        gate.shouldEmit(Instrument.MNQ, "ICE|BID|80000", t0, 60);

        // 61s later — cooldown elapsed
        assertThat(gate.shouldEmit(Instrument.MNQ, "ICE|BID|80000", t0.plusSeconds(61), 60)).isTrue();
    }

    @Test
    void differentKeysAreIndependent() {
        RecentSignalGate gate = new RecentSignalGate();
        gate.shouldEmit(Instrument.MNQ, "ICE|BID|80000", t0, 60);

        // Different price level → not suppressed
        assertThat(gate.shouldEmit(Instrument.MNQ, "ICE|BID|80004", t0.plusSeconds(1), 60)).isTrue();
        // Different side → not suppressed
        assertThat(gate.shouldEmit(Instrument.MNQ, "ICE|ASK|80000", t0.plusSeconds(1), 60)).isTrue();
    }

    @Test
    void differentInstrumentsAreIndependent() {
        RecentSignalGate gate = new RecentSignalGate();
        gate.shouldEmit(Instrument.MNQ, "SPF|BID|80000", t0, 30);

        // Same key, different instrument → not suppressed
        assertThat(gate.shouldEmit(Instrument.MCL, "SPF|BID|80000", t0.plusSeconds(5), 30)).isTrue();
    }

    @Test
    void resetClearsHistory() {
        RecentSignalGate gate = new RecentSignalGate();
        gate.shouldEmit(Instrument.MNQ, "ICE|BID|80000", t0, 60);
        gate.reset();
        // After reset the same key is treated as new even within the cooldown
        assertThat(gate.shouldEmit(Instrument.MNQ, "ICE|BID|80000", t0.plusSeconds(5), 60)).isTrue();
    }
}
