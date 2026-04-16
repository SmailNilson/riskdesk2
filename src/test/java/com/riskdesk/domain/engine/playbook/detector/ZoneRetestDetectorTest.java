package com.riskdesk.domain.engine.playbook.detector;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.playbook.model.PlaybookInput;
import com.riskdesk.domain.engine.playbook.model.SetupCandidate;
import com.riskdesk.domain.engine.playbook.model.SmcEqualLevel;
import com.riskdesk.domain.engine.playbook.model.SmcFvg;
import com.riskdesk.domain.engine.playbook.model.SmcOrderBlock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-10 · Proximity must be scaled by ATR, not by the zone's own width.
 *
 * <p>Why this matters: the old formula {@code distance / zoneSize} produced
 * wrong behaviour at both extremes. A 1-pt FVG was declared "far" the moment
 * price drifted 1.5 pts (noise), while a 50-pt OB was declared "near" even 75
 * pts away. ATR scaling keeps the gate sane across zone sizes and across
 * volatility regimes.
 */
class ZoneRetestDetectorTest {

    private final ZoneRetestDetector detector = new ZoneRetestDetector();

    // ── Helpers ────────────────────────────────────────────────────────────

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    private static PlaybookInput inputWith(BigDecimal lastPrice, BigDecimal atr,
                                           List<SmcOrderBlock> obs,
                                           List<SmcFvg> fvgs) {
        return new PlaybookInput(
            "BULLISH", "BULLISH",
            bd("110"), bd("90"), lastPrice,
            List.of(),
            obs, List.of(), fvgs,
            List.<SmcEqualLevel>of(), List.<SmcEqualLevel>of(),
            null, null, null, null, null, null, null, atr
        );
    }

    private static SmcOrderBlock bullishOb(String high, String low) {
        BigDecimal h = bd(high);
        BigDecimal l = bd(low);
        BigDecimal mid = h.add(l).divide(bd("2"));
        return new SmcOrderBlock("BULLISH", "ACTIVE", h, l, mid, null);
    }

    // ── Price inside the zone — always included ───────────────────────────

    @Test
    void priceInsideZone_isIncluded_withZeroBoundaryDistance() {
        SmcOrderBlock ob = bullishOb("95.00", "93.00");
        PlaybookInput input = inputWith(bd("94.00"), bd("1.00"), List.of(ob), List.of());

        List<SetupCandidate> candidates = detector.detect(input, Direction.LONG);

        assertThat(candidates).hasSize(1);
        SetupCandidate c = candidates.get(0);
        assertThat(c.priceInZone()).isTrue();
        assertThat(c.distanceFromPrice())
            .as("boundary distance should be zero when price is inside the zone")
            .isEqualTo(0.0);
    }

    // ── ATR-relative gate at the edge ─────────────────────────────────────

    @Test
    void priceWithin1_5Atr_ofZoneEdge_isIncluded() {
        // Zone 93-95, ATR=1.0, price=96.40 → 1.40 above edge → within 1.5×ATR = 1.5 → in.
        SmcOrderBlock ob = bullishOb("95.00", "93.00");
        PlaybookInput input = inputWith(bd("96.40"), bd("1.00"), List.of(ob), List.of());

        List<SetupCandidate> candidates = detector.detect(input, Direction.LONG);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).distanceFromPrice()).isEqualTo(1.40);
    }

    @Test
    void priceBeyond1_5Atr_isExcluded() {
        // Zone 93-95, ATR=1.0, price=96.60 → 1.60 above edge → beyond 1.5×ATR = 1.5 → out.
        SmcOrderBlock ob = bullishOb("95.00", "93.00");
        PlaybookInput input = inputWith(bd("96.60"), bd("1.00"), List.of(ob), List.of());

        assertThat(detector.detect(input, Direction.LONG)).isEmpty();
    }

    // ── This is the old bug: zone-size normalisation passed, ATR rejects ──

    @Test
    void wideZoneFarAway_isNowExcluded_whenAtrIsSmall() {
        // Zone 50pts wide (80-130), ATR=1.0, price=140 → 10 pts above edge.
        // Old: distance-from-mid 35, zoneSize 50, proximity 0.70 ≤ 1.5 → INCLUDED (wrong).
        // New: boundary distance 10, threshold 1.5×1.0 = 1.5 → EXCLUDED (correct).
        SmcOrderBlock ob = bullishOb("130.00", "80.00");
        PlaybookInput input = inputWith(bd("140.00"), bd("1.00"), List.of(ob), List.of());

        assertThat(detector.detect(input, Direction.LONG))
            .as("wide zone 10 pts away with tiny ATR must not be considered 'near'")
            .isEmpty();
    }

    @Test
    void narrowZoneNearPrice_isNowIncluded_whenAtrIsLarge() {
        // 0.20-pt FVG at 100.10-100.30, ATR=2.0, price=101.30 → 1.00 above edge.
        // Old: distance-from-mid 1.10, zoneSize 0.20, proximity 5.5 > 1.5 → EXCLUDED (wrong).
        // New: boundary distance 1.00, threshold 1.5×2.0 = 3.0 → INCLUDED (correct).
        SmcFvg fvg = new SmcFvg("BULLISH", bd("100.30"), bd("100.10"));
        PlaybookInput input = inputWith(bd("101.30"), bd("2.00"), List.of(), List.of(fvg));

        List<SetupCandidate> candidates = detector.detect(input, Direction.LONG);

        assertThat(candidates)
            .as("narrow zone within 0.5 ATR of price must be considered 'near' even though it is wider in FVG-widths")
            .hasSize(1);
    }

    // ── Volatility sensitivity: same geometry, different ATR → different verdict ──

    @Test
    void sameGeometry_differentAtr_flipsProximityVerdict() {
        // Zone 93-95, price 96.40 → 1.40 above edge.
        SmcOrderBlock ob = bullishOb("95.00", "93.00");

        PlaybookInput lowVol  = inputWith(bd("96.40"), bd("0.50"), List.of(ob), List.of());
        PlaybookInput highVol = inputWith(bd("96.40"), bd("1.00"), List.of(ob), List.of());

        // Low-vol: threshold 0.75 — 1.40 is two ATR away → out.
        assertThat(detector.detect(lowVol, Direction.LONG)).isEmpty();
        // High-vol: threshold 1.50 — 1.40 is within one ATR → in.
        assertThat(detector.detect(highVol, Direction.LONG)).hasSize(1);
    }

    // ── Fail-open ─────────────────────────────────────────────────────────

    @Test
    void nullAtr_returnsNoCandidates() {
        SmcOrderBlock ob = bullishOb("95.00", "93.00");
        PlaybookInput input = inputWith(bd("94.00"), null, List.of(ob), List.of());

        assertThat(detector.detect(input, Direction.LONG)).isEmpty();
    }

    @Test
    void zeroAtr_returnsNoCandidates() {
        SmcOrderBlock ob = bullishOb("95.00", "93.00");
        PlaybookInput input = inputWith(bd("94.00"), BigDecimal.ZERO, List.of(ob), List.of());

        assertThat(detector.detect(input, Direction.LONG)).isEmpty();
    }

    @Test
    void nullLastPrice_returnsNoCandidates() {
        SmcOrderBlock ob = bullishOb("95.00", "93.00");
        PlaybookInput input = inputWith(null, bd("1.00"), List.of(ob), List.of());

        assertThat(detector.detect(input, Direction.LONG)).isEmpty();
    }

    @Test
    void degenerateZone_highEqualsLow_skipped() {
        SmcOrderBlock degenerate = new SmcOrderBlock(
            "BULLISH", "ACTIVE", bd("95.00"), bd("95.00"), bd("95.00"), null);
        PlaybookInput input = inputWith(bd("95.00"), bd("1.00"), List.of(degenerate), List.of());

        assertThat(detector.detect(input, Direction.LONG)).isEmpty();
    }

    // ── Alignment still enforced ──────────────────────────────────────────

    @Test
    void bearishOb_forLongDirection_skipped() {
        SmcOrderBlock bearish = new SmcOrderBlock("BEARISH", "ACTIVE",
            bd("95.00"), bd("93.00"), bd("94.00"), null);
        PlaybookInput input = inputWith(bd("94.00"), bd("1.00"), List.of(bearish), List.of());

        assertThat(detector.detect(input, Direction.LONG)).isEmpty();
    }
}
