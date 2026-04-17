package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.engine.strategy.model.FvgZone;
import com.riskdesk.domain.engine.strategy.model.LiquidityLevel;
import com.riskdesk.domain.engine.strategy.model.OrderBlockZone;
import com.riskdesk.domain.engine.strategy.model.ZoneContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Transforms the list-heavy {@link IndicatorSnapshot} zones into strategy-layer
 * records, keeping only those within a proximity window around the last price.
 *
 * <p>Filtering here (not in the agent) means every ZONE agent sees the same
 * pre-filtered list — no duplicate distance math across agents.
 */
@Component
public class ZoneContextBuilder {

    /**
     * Proximity window = 1.0 × ATR on each side. An OB five ATR away is not part of
     * "nearby zones" even if technically still active.
     */
    private static final double PROXIMITY_ATR_MULT = 1.0;

    public ZoneContext build(IndicatorSnapshot snapshot, BigDecimal atr) {
        BigDecimal price = snapshot.lastPrice();
        if (price == null || atr == null) return ZoneContext.empty();

        BigDecimal band = atr.multiply(BigDecimal.valueOf(PROXIMITY_ATR_MULT));
        BigDecimal floor = price.subtract(band);
        BigDecimal ceiling = price.add(band);

        List<OrderBlockZone> obs = new ArrayList<>();
        if (snapshot.activeOrderBlocks() != null) {
            for (IndicatorSnapshot.OrderBlockView v : snapshot.activeOrderBlocks()) {
                if (v.high() == null || v.low() == null) continue;
                if (v.high().compareTo(floor) < 0 || v.low().compareTo(ceiling) > 0) continue;
                boolean bullish = isBullish(v.type(), v.originalType());
                BigDecimal mid = v.mid() != null
                    ? v.mid()
                    : v.high().add(v.low()).divide(BigDecimal.valueOf(2), RoundingMode.HALF_UP);
                Double quality = v.obLiveScore() != null ? v.obLiveScore() : v.obFormationScore();
                obs.add(new OrderBlockZone(bullish, v.high(), v.low(), mid, quality));
            }
        }

        List<FvgZone> fvgs = new ArrayList<>();
        if (snapshot.activeFairValueGaps() != null) {
            for (IndicatorSnapshot.FairValueGapView v : snapshot.activeFairValueGaps()) {
                if (v.top() == null || v.bottom() == null) continue;
                if (v.top().compareTo(floor) < 0 || v.bottom().compareTo(ceiling) > 0) continue;
                boolean bullish = "BULLISH".equalsIgnoreCase(v.bias());
                // filledPct from snapshot is not exposed — treat as unfilled (0.0) by default.
                fvgs.add(new FvgZone(bullish, v.top(), v.bottom(), 0.0));
            }
        }

        List<LiquidityLevel> liquidity = new ArrayList<>();
        if (snapshot.equalHighs() != null) {
            for (IndicatorSnapshot.EqualLevelView v : snapshot.equalHighs()) {
                if (v.price() == null) continue;
                if (v.price().compareTo(floor) < 0 || v.price().compareTo(ceiling) > 0) continue;
                liquidity.add(new LiquidityLevel(v.price(), true, v.touchCount()));
            }
        }
        if (snapshot.equalLows() != null) {
            for (IndicatorSnapshot.EqualLevelView v : snapshot.equalLows()) {
                if (v.price() == null) continue;
                if (v.price().compareTo(floor) < 0 || v.price().compareTo(ceiling) > 0) continue;
                liquidity.add(new LiquidityLevel(v.price(), false, v.touchCount()));
            }
        }

        return new ZoneContext(obs, fvgs, liquidity);
    }

    /**
     * Map the legacy OB type strings onto a boolean. A bullish OB is a demand block
     * (type contains "BULLISH") or a bearish breaker (originalType BEARISH, now flipped).
     */
    private static boolean isBullish(String type, String originalType) {
        if (type == null) return true;
        String t = type.toUpperCase();
        if (t.contains("BULLISH") || t.contains("DEMAND")) return true;
        if (t.contains("BEARISH") || t.contains("SUPPLY")) return false;
        if (t.contains("BREAKER") && originalType != null) {
            // a bearish OB that broke becomes a bullish breaker, and vice-versa
            return originalType.toUpperCase().contains("BEARISH");
        }
        return true;
    }
}
