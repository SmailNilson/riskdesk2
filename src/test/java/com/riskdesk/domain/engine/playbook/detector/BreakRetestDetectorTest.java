package com.riskdesk.domain.engine.playbook.detector;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.playbook.model.PlaybookInput;
import com.riskdesk.domain.engine.playbook.model.SetupCandidate;
import com.riskdesk.domain.engine.playbook.model.SetupType;
import com.riskdesk.domain.engine.playbook.model.SmcBreak;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-9 · The retest detector must accept both BOS and CHoCH breaks. BOS
 * retests play continuation; CHoCH retests play the first pullback after a
 * trend flip. Alignment with the trade direction — not the structure type —
 * is what keeps counter-trend setups out.
 */
class BreakRetestDetectorTest {

    private final BreakRetestDetector detector = new BreakRetestDetector();

    private static final BigDecimal ATR_1_0 = new BigDecimal("1.00");

    private static PlaybookInput inputWithBreak(BigDecimal lastPrice, SmcBreak brk, BigDecimal atr) {
        return new PlaybookInput(
            "BULLISH", "BULLISH",
            new BigDecimal("110"), new BigDecimal("90"), lastPrice,
            List.of(brk),
            List.of(), List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null, null, null, atr
        );
    }

    // ── BOS retest (continuation) ──────────────────────────────────────────

    @Test
    void bosRetest_alignedAndWithinProximity_producesCandidate() {
        SmcBreak bos = new SmcBreak("BOS", "BULLISH", new BigDecimal("100.00"), "SWING", 0.85);
        PlaybookInput input = inputWithBreak(new BigDecimal("100.20"), bos, ATR_1_0);

        List<SetupCandidate> candidates = detector.detect(input, Direction.LONG);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).type()).isEqualTo(SetupType.BREAK_RETEST);
        assertThat(candidates.get(0).zoneName()).startsWith("BOS Retest @ ");
    }

    // ── CHoCH retest (reversal — PR-9's new behaviour) ─────────────────────

    @Test
    void chochRetest_alignedAndWithinProximity_producesCandidate() {
        // Bullish CHoCH = bias has just flipped to up. A retest of the level is
        // often the first and cleanest entry into the new trend.
        SmcBreak choch = new SmcBreak("CHOCH", "BULLISH", new BigDecimal("100.00"), "SWING", 0.85);
        PlaybookInput input = inputWithBreak(new BigDecimal("100.20"), choch, ATR_1_0);

        List<SetupCandidate> candidates = detector.detect(input, Direction.LONG);

        assertThat(candidates)
            .as("CHoCH retests must be accepted — they are the cleanest fresh-trend entries")
            .hasSize(1);
        assertThat(candidates.get(0).zoneName()).startsWith("CHoCH Retest @ ");
        assertThat(candidates.get(0).type()).isEqualTo(SetupType.BREAK_RETEST);
    }

    @Test
    void chochRetest_lowercaseType_normalisedAndAccepted() {
        // Defensive: some upstream serialisations may produce lowercase.
        SmcBreak choch = new SmcBreak("choch", "BULLISH", new BigDecimal("100.00"), "SWING", 0.85);
        PlaybookInput input = inputWithBreak(new BigDecimal("100.20"), choch, ATR_1_0);

        List<SetupCandidate> candidates = detector.detect(input, Direction.LONG);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).zoneName()).startsWith("CHoCH Retest @ ");
    }

    // ── Alignment is still enforced — structure type is not a blanket pass ──

    @Test
    void bearishChochAgainstLongDirection_rejected() {
        // LONG bias but a BEARISH CHoCH just printed → alignment fails → skip.
        SmcBreak choch = new SmcBreak("CHOCH", "BEARISH", new BigDecimal("100.00"), "SWING", 0.85);
        PlaybookInput input = inputWithBreak(new BigDecimal("100.20"), choch, ATR_1_0);

        List<SetupCandidate> candidates = detector.detect(input, Direction.LONG);

        assertThat(candidates)
            .as("A counter-trend CHoCH must not generate a retest setup")
            .isEmpty();
    }

    @Test
    void bullishChochShortDirection_rejected() {
        SmcBreak choch = new SmcBreak("CHOCH", "BULLISH", new BigDecimal("100.00"), "SWING", 0.85);
        PlaybookInput input = inputWithBreak(new BigDecimal("100.20"), choch, ATR_1_0);

        List<SetupCandidate> candidates = detector.detect(input, Direction.SHORT);

        assertThat(candidates).isEmpty();
    }

    // ── Confidence & proximity filters still apply to CHoCH ────────────────

    @Test
    void chochBelowConfidenceFloor_rejected() {
        SmcBreak choch = new SmcBreak("CHOCH", "BULLISH", new BigDecimal("100.00"), "SWING", 0.50);
        PlaybookInput input = inputWithBreak(new BigDecimal("100.20"), choch, ATR_1_0);

        List<SetupCandidate> candidates = detector.detect(input, Direction.LONG);

        assertThat(candidates)
            .as("A low-confidence CHoCH is no more reliable than a low-confidence BOS")
            .isEmpty();
    }

    @Test
    void chochFarFromPrice_noRetest() {
        // ATR = 1.00, tolerance = 0.5 × 1.00 = 0.50. Distance = 0.80 > 0.50 → no retest.
        SmcBreak choch = new SmcBreak("CHOCH", "BULLISH", new BigDecimal("100.00"), "SWING", 0.85);
        PlaybookInput input = inputWithBreak(new BigDecimal("100.80"), choch, ATR_1_0);

        List<SetupCandidate> candidates = detector.detect(input, Direction.LONG);

        assertThat(candidates).isEmpty();
    }

    // ── Misc / fail-open ───────────────────────────────────────────────────

    @Test
    void unknownStructureType_ignored() {
        SmcBreak weird = new SmcBreak("FVG_FLIP", "BULLISH", new BigDecimal("100.00"), "SWING", 0.85);
        PlaybookInput input = inputWithBreak(new BigDecimal("100.20"), weird, ATR_1_0);

        assertThat(detector.detect(input, Direction.LONG)).isEmpty();
    }

    @Test
    void nullBreakType_ignored() {
        SmcBreak nullType = new SmcBreak(null, "BULLISH", new BigDecimal("100.00"), "SWING", 0.85);
        PlaybookInput input = inputWithBreak(new BigDecimal("100.20"), nullType, ATR_1_0);

        assertThat(detector.detect(input, Direction.LONG)).isEmpty();
    }

    @Test
    void bosAndChoch_together_produceTwoCandidates() {
        SmcBreak bos = new SmcBreak("BOS", "BULLISH", new BigDecimal("100.00"), "SWING", 0.85);
        SmcBreak choch = new SmcBreak("CHOCH", "BULLISH", new BigDecimal("99.90"), "SWING", 0.85);
        PlaybookInput input = new PlaybookInput(
            "BULLISH", "BULLISH",
            new BigDecimal("110"), new BigDecimal("90"), new BigDecimal("100.10"),
            List.of(bos, choch),
            List.of(), List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null, null, null, ATR_1_0
        );

        List<SetupCandidate> candidates = detector.detect(input, Direction.LONG);

        assertThat(candidates).hasSize(2);
        assertThat(candidates).extracting(SetupCandidate::zoneName)
            .anyMatch(n -> n.startsWith("BOS Retest"))
            .anyMatch(n -> n.startsWith("CHoCH Retest"));
    }

    @Test
    void nullAtr_noCandidates() {
        SmcBreak bos = new SmcBreak("BOS", "BULLISH", new BigDecimal("100.00"), "SWING", 0.85);
        PlaybookInput input = inputWithBreak(new BigDecimal("100.20"), bos, null);

        assertThat(detector.detect(input, Direction.LONG)).isEmpty();
    }

    @Test
    void zeroAtr_noCandidates() {
        SmcBreak bos = new SmcBreak("BOS", "BULLISH", new BigDecimal("100.00"), "SWING", 0.85);
        PlaybookInput input = inputWithBreak(new BigDecimal("100.20"), bos, BigDecimal.ZERO);

        assertThat(detector.detect(input, Direction.LONG)).isEmpty();
    }
}
