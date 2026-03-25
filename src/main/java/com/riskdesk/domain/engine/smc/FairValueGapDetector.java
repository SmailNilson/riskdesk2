package com.riskdesk.domain.engine.smc;

import com.riskdesk.domain.model.Candle;
import java.math.BigDecimal;
import java.util.*;

/**
 * Fair Value Gap (FVG) detector — mirrors LuxAlgo Smart Money Concepts algorithm.
 *
 * Bullish FVG : candle[i].low  > candle[i-2].high  (gap above)
 * Bearish FVG : candle[i].high < candle[i-2].low   (gap below)
 *
 * Candles must be sorted oldest → newest (index 0 = oldest).
 * A gap is mitigated once price re-enters it on any subsequent candle.
 */
public class FairValueGapDetector {

    private final int maxActive;

    public FairValueGapDetector(int maxActive) {
        this.maxActive = maxActive;
    }

    public FairValueGapDetector() {
        this(5);
    }

    /**
     * One Fair Value Gap.
     *
     * @param bias         "BULLISH" or "BEARISH"
     * @param top          upper price boundary of the gap
     * @param bottom       lower price boundary of the gap
     * @param startBarTime epoch-seconds of the middle (gap) candle
     */
    public record FairValueGap(
            String     bias,
            BigDecimal top,
            BigDecimal bottom,
            long       startBarTime
    ) {}

    /** Detect and return up to {@code maxActive} most-recent unmitigated FVGs. */
    public List<FairValueGap> detect(List<Candle> candles) {
        if (candles.size() < 3) return Collections.emptyList();

        List<FairValueGap> all = new ArrayList<>();

        for (int i = 2; i < candles.size(); i++) {
            Candle prev2 = candles.get(i - 2);
            Candle prev1 = candles.get(i - 1); // middle "FVG" candle
            Candle curr  = candles.get(i);

            // Bullish FVG: gap between prev2.high (bottom) and curr.low (top)
            if (curr.getLow().compareTo(prev2.getHigh()) > 0) {
                all.add(new FairValueGap(
                        "BULLISH",
                        curr.getLow(),
                        prev2.getHigh(),
                        prev1.getTimestamp().getEpochSecond()
                ));
            }

            // Bearish FVG: gap between curr.high (bottom) and prev2.low (top)
            if (curr.getHigh().compareTo(prev2.getLow()) < 0) {
                all.add(new FairValueGap(
                        "BEARISH",
                        prev2.getLow(),
                        curr.getHigh(),
                        prev1.getTimestamp().getEpochSecond()
                ));
            }
        }

        // Keep only gaps that have not been filled yet
        List<FairValueGap> active = new ArrayList<>();
        for (FairValueGap fvg : all) {
            if (!isMitigated(fvg, candles)) {
                active.add(fvg);
            }
        }

        // Return up to maxActive most-recent
        int from = Math.max(0, active.size() - maxActive);
        return Collections.unmodifiableList(new ArrayList<>(active.subList(from, active.size())));
    }

    /** A gap is mitigated when price re-enters it on any candle after its formation. */
    private boolean isMitigated(FairValueGap fvg, List<Candle> candles) {
        boolean past = false;
        for (Candle c : candles) {
            long t = c.getTimestamp().getEpochSecond();
            if (!past) {
                if (t > fvg.startBarTime()) past = true;
                else continue;
            }
            if ("BULLISH".equals(fvg.bias()) && c.getLow().compareTo(fvg.bottom())  < 0) return true;
            if ("BEARISH".equals(fvg.bias()) && c.getHigh().compareTo(fvg.top())    > 0) return true;
        }
        return false;
    }
}
