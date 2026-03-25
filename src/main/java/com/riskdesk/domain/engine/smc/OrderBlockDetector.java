package com.riskdesk.domain.engine.smc;

import com.riskdesk.domain.model.Candle;
import java.math.BigDecimal;
import java.util.*;

/**
 * Order Block & Breaker Block detection.
 * An Order Block is the last opposing candle before an impulsive move.
 * Settings: lookback 10, max OB count 3, max BB count 3 (matching LuxAlgo config).
 */
public class OrderBlockDetector {

    private final int lookback;
    private final int maxOrderBlocks;
    private final double impulseFactor; // minimum body/range ratio for impulsive move

    public OrderBlockDetector(int lookback, int maxOrderBlocks, double impulseFactor) {
        this.lookback = lookback;
        this.maxOrderBlocks = maxOrderBlocks;
        this.impulseFactor = impulseFactor;
    }

    public OrderBlockDetector() {
        this(10, 3, 0.5);
    }

    public enum OBType { BULLISH, BEARISH }
    public enum OBStatus { ACTIVE, MITIGATED, BREAKER }

    public record OrderBlock(
            OBType type,
            OBStatus status,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            int formationIndex,
            int mitigationIndex
    ) {
        public BigDecimal midPoint() {
            return highPrice.add(lowPrice).divide(BigDecimal.TWO, 5, java.math.RoundingMode.HALF_UP);
        }
    }

    public List<OrderBlock> detect(List<Candle> candles) {
        if (candles.size() < lookback + 3) return Collections.emptyList();

        List<OrderBlock> orderBlocks = new ArrayList<>();

        for (int i = lookback; i < candles.size() - 2; i++) {
            Candle current = candles.get(i);
            Candle next = candles.get(i + 1);
            Candle confirm = candles.get(i + 2);

            // Bullish OB: bearish candle followed by strong bullish move
            if (current.isBearish() && isBullishImpulse(next, confirm)) {
                OrderBlock ob = new OrderBlock(
                        OBType.BULLISH, OBStatus.ACTIVE,
                        current.getOpen(), current.getLow(),
                        i, -1
                );
                orderBlocks.add(ob);
            }

            // Bearish OB: bullish candle followed by strong bearish move
            if (current.isBullish() && isBearishImpulse(next, confirm)) {
                OrderBlock ob = new OrderBlock(
                        OBType.BEARISH, OBStatus.ACTIVE,
                        current.getHigh(), current.getOpen(),
                        i, -1
                );
                orderBlocks.add(ob);
            }
        }

        // Check mitigation (price returned to OB zone)
        List<OrderBlock> result = new ArrayList<>();
        for (OrderBlock ob : orderBlocks) {
            OBStatus status = ob.status();
            int mitigationIdx = -1;

            for (int j = ob.formationIndex() + 3; j < candles.size(); j++) {
                Candle c = candles.get(j);
                if (ob.type() == OBType.BULLISH && c.getLow().compareTo(ob.highPrice()) <= 0) {
                    // Price returned to bullish OB — could be mitigation or reaction
                    if (c.getClose().compareTo(ob.lowPrice()) < 0) {
                        status = OBStatus.MITIGATED;
                        mitigationIdx = j;
                        break;
                    }
                }
                if (ob.type() == OBType.BEARISH && c.getHigh().compareTo(ob.lowPrice()) >= 0) {
                    if (c.getClose().compareTo(ob.highPrice()) > 0) {
                        status = OBStatus.MITIGATED;
                        mitigationIdx = j;
                        break;
                    }
                }
            }

            result.add(new OrderBlock(ob.type(), status, ob.highPrice(), ob.lowPrice(),
                    ob.formationIndex(), mitigationIdx));
        }

        // Return only active (unmitigated) OBs, capped at maxOrderBlocks per type
        List<OrderBlock> activeOBs = result.stream()
                .filter(ob -> ob.status() == OBStatus.ACTIVE)
                .toList();

        List<OrderBlock> finalResult = new ArrayList<>();
        long bullishCount = 0, bearishCount = 0;
        for (int i = activeOBs.size() - 1; i >= 0; i--) {
            OrderBlock ob = activeOBs.get(i);
            if (ob.type() == OBType.BULLISH && bullishCount < maxOrderBlocks) {
                finalResult.add(ob);
                bullishCount++;
            } else if (ob.type() == OBType.BEARISH && bearishCount < maxOrderBlocks) {
                finalResult.add(ob);
                bearishCount++;
            }
        }

        return finalResult;
    }

    private boolean isBullishImpulse(Candle next, Candle confirm) {
        if (!next.isBullish()) return false;
        BigDecimal bodyRatio = next.body().doubleValue() == 0 ? BigDecimal.ZERO :
                next.body().divide(next.range().max(BigDecimal.ONE), 4, java.math.RoundingMode.HALF_UP);
        return bodyRatio.doubleValue() >= impulseFactor && confirm.isBullish();
    }

    private boolean isBearishImpulse(Candle next, Candle confirm) {
        if (!next.isBearish()) return false;
        BigDecimal bodyRatio = next.body().doubleValue() == 0 ? BigDecimal.ZERO :
                next.body().divide(next.range().max(BigDecimal.ONE), 4, java.math.RoundingMode.HALF_UP);
        return bodyRatio.doubleValue() >= impulseFactor && confirm.isBearish();
    }
}
