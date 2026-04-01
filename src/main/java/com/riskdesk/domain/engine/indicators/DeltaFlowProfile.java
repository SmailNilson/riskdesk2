package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Delta Flow Profile — buy vs sell volume delta.
 *
 * Splits tick volume into buy/sell using the CLV (Close Location Value) ratio,
 * the same approach as the Accumulation/Distribution line but focused on delta.
 *
 *   CLV = ((Close - Low) - (High - Close)) / (High - Low)  ∈ [-1, +1]
 *   Buy volume  = Volume * max(CLV, 0)   (positive CLV → buying pressure)
 *   Sell volume = Volume * max(-CLV, 0)  (negative CLV → selling pressure)
 *   Delta       = Buy - Sell
 *
 * Cumulative delta shows whether buyers or sellers are in control.
 */
public class DeltaFlowProfile {

    private final int lookback; // period for rolling delta stats

    public DeltaFlowProfile(int lookback) {
        this.lookback = lookback;
    }

    public DeltaFlowProfile() {
        this(20);
    }

    public record DeltaBar(
            BigDecimal buyVolume,
            BigDecimal sellVolume,
            BigDecimal delta,        // buy - sell
            BigDecimal cumulativeDelta,
            String bias              // "BUYING" | "SELLING" | "NEUTRAL"
    ) {}

    public record DeltaFlowResult(
            BigDecimal currentDelta,
            BigDecimal cumulativeDelta,
            BigDecimal rollingBuyVolume,    // sum of buy vol over lookback
            BigDecimal rollingSellVolume,   // sum of sell vol over lookback
            BigDecimal buyRatio,            // buyVol / totalVol [0..1]
            String bias
    ) {}

    public List<DeltaBar> calculate(List<Candle> candles) {
        if (candles.isEmpty()) return Collections.emptyList();

        List<DeltaBar> bars = new ArrayList<>();
        BigDecimal cumDelta = BigDecimal.ZERO;

        for (Candle c : candles) {
            BigDecimal range = c.getHigh().subtract(c.getLow());
            BigDecimal buyVol, sellVol;

            if (range.compareTo(BigDecimal.ZERO) == 0) {
                // Doji — split evenly
                BigDecimal half = BigDecimal.valueOf(c.getVolume())
                        .divide(BigDecimal.TWO, 2, RoundingMode.HALF_UP);
                buyVol = half;
                sellVol = half;
            } else {
                // CLV = ((C-L) - (H-C)) / (H-L)
                BigDecimal clv = c.getClose().subtract(c.getLow())
                        .subtract(c.getHigh().subtract(c.getClose()))
                        .divide(range, 10, RoundingMode.HALF_UP);

                BigDecimal vol = BigDecimal.valueOf(c.getVolume());
                buyVol = clv.compareTo(BigDecimal.ZERO) > 0
                        ? vol.multiply(clv).setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                sellVol = clv.compareTo(BigDecimal.ZERO) < 0
                        ? vol.multiply(clv.abs()).setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
            }

            BigDecimal delta = buyVol.subtract(sellVol);
            cumDelta = cumDelta.add(delta);

            String bias = delta.compareTo(BigDecimal.ZERO) > 0 ? "BUYING"
                    : delta.compareTo(BigDecimal.ZERO) < 0 ? "SELLING" : "NEUTRAL";

            bars.add(new DeltaBar(buyVol, sellVol, delta, cumDelta, bias));
        }

        return bars;
    }

    public DeltaFlowResult current(List<Candle> candles) {
        List<DeltaBar> bars = calculate(candles);
        if (bars.isEmpty()) return null;

        DeltaBar last = bars.get(bars.size() - 1);

        // Rolling sums over the lookback window
        int from = Math.max(0, bars.size() - lookback);
        BigDecimal rollingBuy  = BigDecimal.ZERO;
        BigDecimal rollingSell = BigDecimal.ZERO;
        for (int i = from; i < bars.size(); i++) {
            rollingBuy  = rollingBuy.add(bars.get(i).buyVolume());
            rollingSell = rollingSell.add(bars.get(i).sellVolume());
        }

        BigDecimal total = rollingBuy.add(rollingSell);
        BigDecimal buyRatio = total.compareTo(BigDecimal.ZERO) == 0
                ? new BigDecimal("0.5")
                : rollingBuy.divide(total, 4, RoundingMode.HALF_UP);

        // UC-ALERT-0006: thresholds 0.55/0.45 hardcoded — migrate to AlertDefinition config
        String bias = buyRatio.compareTo(new BigDecimal("0.55")) > 0 ? "BUYING"
                : buyRatio.compareTo(new BigDecimal("0.45")) < 0 ? "SELLING" : "NEUTRAL";

        return new DeltaFlowResult(
                last.delta().setScale(2, RoundingMode.HALF_UP),
                last.cumulativeDelta().setScale(2, RoundingMode.HALF_UP),
                rollingBuy.setScale(2, RoundingMode.HALF_UP),
                rollingSell.setScale(2, RoundingMode.HALF_UP),
                buyRatio,
                bias
        );
    }
}
