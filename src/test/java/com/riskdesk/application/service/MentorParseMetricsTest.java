package com.riskdesk.application.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Guards {@link MentorParseMetrics} counter accounting.
 *
 * <p>This is the observability hook that detects silent Gemini degradations:
 * when the tech-failure ratio (recovered + failure over total) climbs, it
 * means the UI is masking technical failures as "Trade Non-Conforme". If
 * the counters stop being incremented or the ratio math breaks, that
 * regression can return undetected.
 */
class MentorParseMetricsTest {

    @Test
    void snapshot_isZeroBeforeAnyRecording() {
        MentorParseMetrics metrics = new MentorParseMetrics();

        MentorParseMetrics.Snapshot snap = metrics.snapshot();

        assertThat(snap.success()).isZero();
        assertThat(snap.recovered()).isZero();
        assertThat(snap.failure()).isZero();
        assertThat(snap.total()).isZero();
        assertThat(snap.techFailureRatio()).isZero();
    }

    @Test
    void snapshot_tracksThreeCounters_withCorrectRatio() {
        MentorParseMetrics metrics = new MentorParseMetrics();

        // 7 strict success, 2 recovered (truncated but salvaged), 1 failure
        for (int i = 0; i < 7; i++) metrics.recordSuccess();
        for (int i = 0; i < 2; i++) metrics.recordRecovered();
        metrics.recordFailure();

        MentorParseMetrics.Snapshot snap = metrics.snapshot();

        assertThat(snap.success()).isEqualTo(7);
        assertThat(snap.recovered()).isEqualTo(2);
        assertThat(snap.failure()).isEqualTo(1);
        assertThat(snap.total()).isEqualTo(10);
        // Tech failures = recovered + failure = 3 / 10 = 0.30
        assertThat(snap.techFailureRatio()).isEqualTo(0.30, within(1e-9));
    }

    @Test
    void snapshot_prodScenario_ratioReflectsMaskedRejections() {
        // Reproduces the prod audit: 70 tradable signals, 67 tech failures
        // (63 unparseable + 3 recovered) and 3 strict success. Ratio should
        // be ~0.957 — well above the 0.10 warn threshold.
        MentorParseMetrics metrics = new MentorParseMetrics();

        for (int i = 0; i < 3; i++) metrics.recordSuccess();
        for (int i = 0; i < 3; i++) metrics.recordRecovered();
        for (int i = 0; i < 64; i++) metrics.recordFailure();

        MentorParseMetrics.Snapshot snap = metrics.snapshot();

        assertThat(snap.total()).isEqualTo(70);
        assertThat(snap.techFailureRatio()).isGreaterThan(0.90);
    }

    @Test
    void techFailureRatio_ignoresDivisionByZero() {
        MentorParseMetrics metrics = new MentorParseMetrics();
        assertThat(metrics.snapshot().techFailureRatio()).isZero();
    }

    @Test
    void missedTrades_startsAtZero_andIncrementsIndependently() {
        // The missed-trade counter reflects recovered responses whose raw
        // text carried an ELIGIBLE verdict — they MUST also be counted as
        // recovered (they're not a separate parse path). Guard that the
        // caller can't silently swap recordRecovered for
        // recordTruncatedButValidated.
        MentorParseMetrics metrics = new MentorParseMetrics();
        assertThat(metrics.snapshot().missedTrades()).isZero();

        metrics.recordRecovered();
        metrics.recordTruncatedButValidated();

        MentorParseMetrics.Snapshot snap = metrics.snapshot();
        assertThat(snap.recovered()).isEqualTo(1);
        assertThat(snap.missedTrades()).isEqualTo(1);
        assertThat(snap.total()).isEqualTo(1);
    }

    @Test
    void missedTrades_doesNotAffectTotalOrTechFailureRatio() {
        // Missed-trades is a classification of recovered counts, not a parse
        // bucket of its own. Incrementing it must never move the total or
        // shift the tech-failure ratio.
        MentorParseMetrics metrics = new MentorParseMetrics();
        for (int i = 0; i < 5; i++) metrics.recordSuccess();
        metrics.recordRecovered();
        metrics.recordTruncatedButValidated();

        MentorParseMetrics.Snapshot snap = metrics.snapshot();

        assertThat(snap.total()).isEqualTo(6);
        assertThat(snap.techFailureRatio()).isEqualTo(1.0 / 6.0, within(1e-9));
        assertThat(snap.missedTrades()).isEqualTo(1);
    }
}
