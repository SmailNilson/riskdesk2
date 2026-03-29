package com.riskdesk.domain.engine.smc;

import com.riskdesk.domain.model.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Order Block & Breaker Block detection.
 * An Order Block is the last opposing candle before an impulsive move.
 * Settings: lookback 10, max OB count 3, max BB count 3 (matching LuxAlgo config).
 *
 * UC-SMC-009: also exposes lifecycle events (MITIGATION / INVALIDATION) so the
 * alert layer can fire on real OB activity instead of the VWAP-inside proxy.
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
    public enum OBEventType { MITIGATION, INVALIDATION }

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

    /**
     * Lifecycle event emitted when an Order Block is mitigated (price tests the zone)
     * or invalidated (price closes through the zone).
     */
    public record OrderBlockEvent(
            OBEventType eventType,
            OBType obType,
            BigDecimal high,
            BigDecimal low,
            int formationIndex,
            int eventBarIndex,
            Instant eventTime
    ) {}

    /** Bundles active OBs with lifecycle events detected on the last bar. */
    public record DetectionResult(
            List<OrderBlock> activeOrderBlocks,
            List<OrderBlock> breakerOrderBlocks,
            List<OrderBlockEvent> events
    ) {}

    /**
     * Legacy API — returns only active (unmitigated) OBs.
     */
    public List<OrderBlock> detect(List<Candle> candles) {
        return detectWithEvents(candles).activeOrderBlocks();
    }

    /**
     * Full detection: returns active OBs plus lifecycle events on the last bar.
     *
     * Mitigation (BULLISH OB): last bar low &le; OB high (price tested demand zone).
     * Invalidation (BULLISH OB): last bar close &lt; OB low (demand zone broken).
     *
     * Mitigation (BEARISH OB): last bar high &ge; OB low (price tested supply zone).
     * Invalidation (BEARISH OB): last bar close &gt; OB high (supply zone broken).
     */
    public DetectionResult detectWithEvents(List<Candle> candles) {
        if (candles.size() < lookback + 3) {
            return new DetectionResult(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        // Phase 1: Identify all candidate OBs
        List<OrderBlock> candidates = new ArrayList<>();

        for (int i = lookback; i < candles.size() - 2; i++) {
            Candle current = candles.get(i);
            Candle next = candles.get(i + 1);
            Candle confirm = candles.get(i + 2);

            // Bullish OB: bearish candle followed by strong bullish move
            if (current.isBearish() && isBullishImpulse(next, confirm)) {
                candidates.add(new OrderBlock(
                        OBType.BULLISH, OBStatus.ACTIVE,
                        current.getOpen(), current.getLow(),
                        i, -1
                ));
            }

            // Bearish OB: bullish candle followed by strong bearish move
            if (current.isBullish() && isBearishImpulse(next, confirm)) {
                candidates.add(new OrderBlock(
                        OBType.BEARISH, OBStatus.ACTIVE,
                        current.getHigh(), current.getOpen(),
                        i, -1
                ));
            }
        }

        // Phase 2: Walk forward — track mitigation/invalidation and collect events on last bar
        int lastBarIdx = candles.size() - 1;
        Candle lastBar = candles.get(lastBarIdx);
        Instant lastBarTime = lastBar.getTimestamp();
        List<OrderBlockEvent> events = new ArrayList<>();
        List<OrderBlock> resolved = new ArrayList<>();

        for (OrderBlock ob : candidates) {
            OBType resolvedType = ob.type();
            OBStatus status = OBStatus.ACTIVE;
            int mitigationIdx = -1;

            for (int j = ob.formationIndex() + 3; j < candles.size(); j++) {
                Candle c = candles.get(j);
                boolean isLastBar = (j == lastBarIdx);

                if (ob.type() == OBType.BULLISH) {
                    // Price entered bullish OB (demand zone)
                    if (c.getLow().compareTo(ob.highPrice()) <= 0) {
                        if (c.getClose().compareTo(ob.lowPrice()) < 0) {
                            // Close below the entire zone → invalidation
                            status = OBStatus.BREAKER;
                            resolvedType = OBType.BEARISH;
                            mitigationIdx = j;
                            if (isLastBar) {
                                events.add(new OrderBlockEvent(
                                        OBEventType.INVALIDATION, ob.type(),
                                        ob.highPrice(), ob.lowPrice(),
                                        ob.formationIndex(), j, lastBarTime));
                            }
                            break;
                        } else {
                            // Touched but held → mitigation (test)
                            if (isLastBar) {
                                events.add(new OrderBlockEvent(
                                        OBEventType.MITIGATION, ob.type(),
                                        ob.highPrice(), ob.lowPrice(),
                                        ob.formationIndex(), j, lastBarTime));
                            }
                        }
                    }
                } else { // BEARISH
                    // Price entered bearish OB (supply zone)
                    if (c.getHigh().compareTo(ob.lowPrice()) >= 0) {
                        if (c.getClose().compareTo(ob.highPrice()) > 0) {
                            // Close above the entire zone → invalidation
                            status = OBStatus.BREAKER;
                            resolvedType = OBType.BULLISH;
                            mitigationIdx = j;
                            if (isLastBar) {
                                events.add(new OrderBlockEvent(
                                        OBEventType.INVALIDATION, ob.type(),
                                        ob.highPrice(), ob.lowPrice(),
                                        ob.formationIndex(), j, lastBarTime));
                            }
                            break;
                        } else {
                            // Touched but held → mitigation (test)
                            if (isLastBar) {
                                events.add(new OrderBlockEvent(
                                        OBEventType.MITIGATION, ob.type(),
                                        ob.highPrice(), ob.lowPrice(),
                                        ob.formationIndex(), j, lastBarTime));
                            }
                        }
                    }
                }
            }

            resolved.add(new OrderBlock(resolvedType, status, ob.highPrice(), ob.lowPrice(),
                    ob.formationIndex(), mitigationIdx));
        }

        // Phase 3: Keep only active OBs and breaker OBs, capped per type
        List<OrderBlock> activeOBs = resolved.stream()
                .filter(ob -> ob.status() == OBStatus.ACTIVE)
                .toList();
        List<OrderBlock> breakerOBs = resolved.stream()
                .filter(ob -> ob.status() == OBStatus.BREAKER)
                .toList();

        List<OrderBlock> finalActive = new ArrayList<>();
        List<OrderBlock> finalBreaker = new ArrayList<>();
        long bullishCount = 0, bearishCount = 0;
        for (int i = activeOBs.size() - 1; i >= 0; i--) {
            OrderBlock ob = activeOBs.get(i);
            if (ob.type() == OBType.BULLISH && bullishCount < maxOrderBlocks) {
                finalActive.add(ob);
                bullishCount++;
            } else if (ob.type() == OBType.BEARISH && bearishCount < maxOrderBlocks) {
                finalActive.add(ob);
                bearishCount++;
            }
        }

        bullishCount = 0;
        bearishCount = 0;
        for (int i = breakerOBs.size() - 1; i >= 0; i--) {
            OrderBlock ob = breakerOBs.get(i);
            if (ob.type() == OBType.BULLISH && bullishCount < maxOrderBlocks) {
                finalBreaker.add(ob);
                bullishCount++;
            } else if (ob.type() == OBType.BEARISH && bearishCount < maxOrderBlocks) {
                finalBreaker.add(ob);
                bearishCount++;
            }
        }

        return new DetectionResult(finalActive, finalBreaker, events);
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
