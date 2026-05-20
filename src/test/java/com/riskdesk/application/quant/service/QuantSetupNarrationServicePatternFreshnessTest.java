package com.riskdesk.application.quant.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.MarketSnapshot;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.model.QuantState;
import com.riskdesk.domain.quant.narrative.QuantNarrator;
import com.riskdesk.domain.quant.pattern.OrderFlowPattern;
import com.riskdesk.domain.quant.pattern.OrderFlowPatternDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the Codex review on PR #299:
 * <a href="https://github.com/SmailNilson/riskdesk2/pull/299#discussion">link</a>.
 * <p>
 * The bug: {@code QuantGateService} called {@code buildNarration} BEFORE
 * adding the current snapshot to the history store, so the
 * {@link OrderFlowPatternDetector} never saw the current price tick.
 * The structural evaluator then consumed a pattern derived from stale
 * prices (last N-1 scans only), and could disagree with the final
 * narration's pattern (computed AFTER the add).
 * <p>
 * The fix: {@link QuantSetupNarrationService#buildNarration} now always
 * appends the snapshot's price to the recent-price window passed to the
 * detector — so the pattern always reflects the current tick, regardless
 * of whether the snapshot has been added to history yet.
 */
class QuantSetupNarrationServicePatternFreshnessTest {

    private static final Instrument INSTR = Instrument.MNQ;
    private static final Instant    NOW   = Instant.parse("2026-04-29T18:00:00Z");
    private static final QuantState STATE = QuantState.reset(LocalDate.of(2026, 4, 29));

    private final OrderFlowPatternDetector detector = new OrderFlowPatternDetector();
    private final QuantNarrator narrator = new QuantNarrator();

    @Test
    @DisplayName("Pattern includes the current tick even when snapshot is NOT in history yet")
    void currentPriceFedToDetector_evenBeforeHistoryAdd() {
        // Arrange: history holds a single past tick at 20_000 (60s ago). The
        // current snapshot has price 20_010 with a strongly negative delta —
        // textbook ABSORPTION_HAUSSIERE (Δ < 0 + price up). Distinct scanTimes
        // so the dedup logic does NOT collapse them.
        QuantSnapshotHistoryStore history = new QuantSnapshotHistoryStore();
        history.add(INSTR, snapshotAtTime(20_000.0, NOW.minusSeconds(60)));
        QuantSetupNarrationService svc = new QuantSetupNarrationService(history, detector, narrator);

        QuantSnapshot current = snapshotAtTime(20_010.0, NOW);
        MarketSnapshot ms = new MarketSnapshot.Builder()
            .now(NOW).price(20_010.0).delta(-300.0).build();

        // Act: caller has NOT yet added `current` to history (matches the
        // QuantGateService order: buildNarration runs first, then add).
        QuantSetupNarrationService.NarrationResult result =
            svc.buildNarration(INSTR, current, STATE, ms);

        // Assert: detector saw both 20_000 (history) and 20_010 (current),
        // so it classified an upward move with negative delta as the
        // bullish-absorption regime — NOT the single-scan VRAIE_VENTE
        // fallback (which is what the pre-fix code returned because the
        // single-element history failed the detector's
        // {@code recentPrices.size() >= 2} guard).
        assertThat(result.pattern().type())
            .as("pattern must reflect current tick, not stale history only")
            .isEqualTo(OrderFlowPattern.ABSORPTION_HAUSSIERE);
    }

    @Test
    @DisplayName("Empty history: single-scan fallback fires (1-price window is below the 2-price comparison guard)")
    void emptyHistory_singleTick_usesSingleScanFallback() {
        // With NO prior history, the recent-price list passed to the
        // detector contains just the current tick (1 element) — below
        // the {@code recentPrices.size() >= 2} comparison guard. The
        // detector then falls back to {@code classifyFromCurrentScan},
        // which flags Δ=-300 as VRAIE_VENTE with WAIT/LOW confidence.
        // Key invariant: the fix must never invent fake history points
        // to short-circuit the guard.
        QuantSnapshotHistoryStore history = new QuantSnapshotHistoryStore();
        QuantSetupNarrationService svc = new QuantSetupNarrationService(history, detector, narrator);

        QuantSnapshot current = snapshotAt(20_000.0);
        MarketSnapshot ms = new MarketSnapshot.Builder()
            .now(NOW).price(20_000.0).delta(-300.0).build();

        QuantSetupNarrationService.NarrationResult result =
            svc.buildNarration(INSTR, current, STATE, ms);

        assertThat(result.pattern().type()).isEqualTo(OrderFlowPattern.VRAIE_VENTE);
        assertThat(result.pattern().action()).isEqualTo(com.riskdesk.domain.quant.pattern.PatternAnalysis.Action.WAIT);
        assertThat(result.pattern().confidence())
            .isEqualTo(com.riskdesk.domain.quant.pattern.PatternAnalysis.Confidence.LOW);
    }

    @Test
    @DisplayName("Codex P1 #299: pre-add and post-add narration passes yield the SAME pattern")
    void prePost_addToHistory_yieldsIdenticalPattern() {
        // This is the regression test for the second Codex review on PR #299:
        // QuantGateService.scan calls buildNarration TWICE — once before
        // historyStore.add (preNarration) and once after (final narration).
        // Both passes MUST classify the same regime; otherwise the
        // structural evaluator can block/unblock SHORT based on a pattern
        // that disagrees with what the user is shown in the markdown.
        //
        // Setup: 3 prior scans on a clear uptrend, current scan continues
        // up with strongly negative delta — textbook ABSORPTION_HAUSSIERE.
        QuantSnapshotHistoryStore history = new QuantSnapshotHistoryStore();
        history.add(INSTR, snapshotAtTime(20_000.0, NOW.minusSeconds(180)));
        history.add(INSTR, snapshotAtTime(20_010.0, NOW.minusSeconds(120)));
        history.add(INSTR, snapshotAtTime(20_020.0, NOW.minusSeconds(60)));
        QuantSetupNarrationService svc = new QuantSetupNarrationService(history, detector, narrator);

        QuantSnapshot current = snapshotAtTime(20_030.0, NOW);
        MarketSnapshot ms = new MarketSnapshot.Builder()
            .now(NOW).price(20_030.0).delta(-450.0).build();

        // Pass 1 — BEFORE the orchestrator adds `current` to history.
        QuantSetupNarrationService.NarrationResult pre =
            svc.buildNarration(INSTR, current, STATE, ms);

        // Pass 2 — AFTER the orchestrator adds `current` to history.
        history.add(INSTR, current);
        QuantSetupNarrationService.NarrationResult post =
            svc.buildNarration(INSTR, current, STATE, ms);

        // Both passes must agree on the pattern type. Without the scanTime-
        // based dedup, pass 1 sees window [20_010, 20_020, 20_030] (move +20)
        // and pass 2 sees a 2-element window [20_020, 20_030] (move +10) —
        // both classify as ABSORPTION_HAUSSIERE in this case but the
        // confidence band differs, and a borderline move can flip the
        // pattern entirely.
        assertThat(post.pattern().type())
            .as("pre-add and post-add must agree — Codex P1 invariant")
            .isEqualTo(pre.pattern().type());
        assertThat(post.pattern().type()).isEqualTo(OrderFlowPattern.ABSORPTION_HAUSSIERE);
        assertThat(post.pattern().confidence())
            .as("confidence must also match (same priceMove → same band)")
            .isEqualTo(pre.pattern().confidence());
    }

    @Test
    @DisplayName("Flat market: pre-add and post-add agree even when consecutive scans share a price")
    void flatMarket_dedupByScanTimeNotByPrice() {
        // A flat market hands consecutive snapshots with IDENTICAL prices.
        // The dedup MUST key on scanTime (not on price) — otherwise two
        // legit history entries with the same price would be collapsed
        // and the window would shrink unexpectedly.
        QuantSnapshotHistoryStore history = new QuantSnapshotHistoryStore();
        history.add(INSTR, snapshotAtTime(20_000.0, NOW.minusSeconds(180)));
        history.add(INSTR, snapshotAtTime(20_000.0, NOW.minusSeconds(120)));
        history.add(INSTR, snapshotAtTime(20_000.0, NOW.minusSeconds(60)));
        QuantSetupNarrationService svc = new QuantSetupNarrationService(history, detector, narrator);

        QuantSnapshot current = snapshotAtTime(20_000.0, NOW);
        MarketSnapshot ms = new MarketSnapshot.Builder()
            .now(NOW).price(20_000.0).delta(-300.0).build();

        QuantSetupNarrationService.NarrationResult pre =
            svc.buildNarration(INSTR, current, STATE, ms);
        history.add(INSTR, current);
        QuantSetupNarrationService.NarrationResult post =
            svc.buildNarration(INSTR, current, STATE, ms);

        // Both pass: priceMove = 0 (flat) + delta < 0 → priceStable + delta<0
        // matches the ABSORPTION_HAUSSIERE branch. Critical: pre and post
        // produce the IDENTICAL pattern, proving that scanTime-based dedup
        // does not collapse the flat-price history.
        assertThat(post.pattern().type()).isEqualTo(pre.pattern().type());
        assertThat(post.pattern().type()).isEqualTo(OrderFlowPattern.ABSORPTION_HAUSSIERE);
    }

    private static QuantSnapshot snapshotAtTime(double price, Instant scanTime) {
        return new QuantSnapshot(
            INSTR,
            Map.<Gate, GateResult>of(),
            0,
            price,
            "LIVE_PUSH",
            0.0,
            ZonedDateTime.ofInstant(scanTime, ZoneOffset.UTC)
        );
    }

    private static QuantSnapshot snapshotAt(double price) {
        return snapshotAtTime(price, NOW);
    }
}
