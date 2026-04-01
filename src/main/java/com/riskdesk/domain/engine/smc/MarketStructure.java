package com.riskdesk.domain.engine.smc;

import com.riskdesk.domain.model.Candle;
import java.math.BigDecimal;
import java.util.*;

/**
 * Smart Money Concepts — Market Structure Analysis.
 * Detects: Swing Highs/Lows, BOS (Break of Structure), CHoCH (Change of Character),
 * Strong/Weak Highs and Lows.
 *
 * @deprecated Use {@link SmcStructureEngine} instead, which provides dual-level
 *             (internal + swing) structure analysis with BOS/CHoCH event tracking.
 *             This class is retained only for the backtest subsystem.
 *             Tracked under UC-ALERT-0006 for future removal.
 */
@Deprecated(forRemoval = true)
public class MarketStructure {

    private final int swingLookback;

    public MarketStructure(int swingLookback) {
        this.swingLookback = swingLookback;
    }

    public MarketStructure() {
        this(5);
    }

    // --- Data structures ---

    public enum StructureType { BOS, CHOCH }
    public enum Trend { BULLISH, BEARISH, UNDEFINED }
    public enum SwingType { HIGH, LOW }
    public enum Strength { STRONG, WEAK }

    public record SwingPoint(SwingType type, BigDecimal price, int index, Strength strength) {}

    public record StructureBreak(
            StructureType type,
            Trend newTrend,
            BigDecimal breakLevel,
            int breakIndex,
            SwingPoint brokenSwing
    ) {}

    public record StructureAnalysis(
            List<SwingPoint> swingPoints,
            List<StructureBreak> breaks,
            Trend currentTrend,
            BigDecimal strongLow,
            BigDecimal strongHigh,
            BigDecimal weakLow,
            BigDecimal weakHigh
    ) {}

    // --- Swing point detection ---

    public List<SwingPoint> detectSwingPoints(List<Candle> candles) {
        List<SwingPoint> swings = new ArrayList<>();
        if (candles.size() < swingLookback * 2 + 1) return swings;

        for (int i = swingLookback; i < candles.size() - swingLookback; i++) {
            if (isSwingHigh(candles, i)) {
                swings.add(new SwingPoint(SwingType.HIGH, candles.get(i).getHigh(), i, Strength.WEAK));
            }
            if (isSwingLow(candles, i)) {
                swings.add(new SwingPoint(SwingType.LOW, candles.get(i).getLow(), i, Strength.WEAK));
            }
        }

        return swings;
    }

    private boolean isSwingHigh(List<Candle> candles, int index) {
        BigDecimal high = candles.get(index).getHigh();
        for (int i = index - swingLookback; i <= index + swingLookback; i++) {
            if (i == index) continue;
            if (candles.get(i).getHigh().compareTo(high) >= 0) return false;
        }
        return true;
    }

    private boolean isSwingLow(List<Candle> candles, int index) {
        BigDecimal low = candles.get(index).getLow();
        for (int i = index - swingLookback; i <= index + swingLookback; i++) {
            if (i == index) continue;
            if (candles.get(i).getLow().compareTo(low) <= 0) return false;
        }
        return true;
    }

    // --- Structure break detection ---

    public StructureAnalysis analyze(List<Candle> candles) {
        List<SwingPoint> swings = detectSwingPoints(candles);
        List<StructureBreak> breaks = new ArrayList<>();
        Trend currentTrend = Trend.UNDEFINED;

        SwingPoint lastSwingHigh = null;
        SwingPoint lastSwingLow = null;
        BigDecimal strongHigh = null, strongLow = null;
        BigDecimal weakHigh = null, weakLow = null;

        for (int i = 0; i < swings.size(); i++) {
            SwingPoint sp = swings.get(i);

            if (sp.type() == SwingType.HIGH) {
                lastSwingHigh = sp;
            } else {
                lastSwingLow = sp;
            }

            // Check for structure breaks in subsequent candles
            if (lastSwingHigh != null && lastSwingLow != null) {
                // Check candles after this swing point for breaks
                int checkFrom = sp.index() + 1;
                int checkTo = Math.min(candles.size(), i + 1 < swings.size() ? swings.get(i + 1).index() : candles.size());

                for (int j = checkFrom; j < checkTo; j++) {
                    Candle c = candles.get(j);

                    // Break above swing high
                    if (c.getClose().compareTo(lastSwingHigh.price()) > 0) {
                        StructureType type;
                        if (currentTrend == Trend.BEARISH || currentTrend == Trend.UNDEFINED) {
                            type = StructureType.CHOCH; // Change of character
                        } else {
                            type = StructureType.BOS; // Continuation
                        }

                        breaks.add(new StructureBreak(type, Trend.BULLISH, lastSwingHigh.price(), j, lastSwingHigh));
                        currentTrend = Trend.BULLISH;

                        // In bullish trend: last swing low = strong low, last swing high = weak high
                        strongLow = lastSwingLow.price();
                        weakHigh = lastSwingHigh.price();
                        break;
                    }

                    // Break below swing low
                    if (c.getClose().compareTo(lastSwingLow.price()) < 0) {
                        StructureType type;
                        if (currentTrend == Trend.BULLISH || currentTrend == Trend.UNDEFINED) {
                            type = StructureType.CHOCH;
                        } else {
                            type = StructureType.BOS;
                        }

                        breaks.add(new StructureBreak(type, Trend.BEARISH, lastSwingLow.price(), j, lastSwingLow));
                        currentTrend = Trend.BEARISH;

                        // In bearish trend: last swing high = strong high, last swing low = weak low
                        strongHigh = lastSwingHigh.price();
                        weakLow = lastSwingLow.price();
                        break;
                    }
                }
            }
        }

        // Mark swing point strengths
        // NOTE: BigDecimal.equals() checks scale too (1.0 != 1.00), so always use compareTo()
        List<SwingPoint> enrichedSwings = new ArrayList<>();
        for (SwingPoint sp : swings) {
            Strength str = Strength.WEAK;
            if (sp.type() == SwingType.LOW  && strongLow  != null && sp.price().compareTo(strongLow)  == 0) str = Strength.STRONG;
            if (sp.type() == SwingType.HIGH && strongHigh != null && sp.price().compareTo(strongHigh) == 0) str = Strength.STRONG;
            enrichedSwings.add(new SwingPoint(sp.type(), sp.price(), sp.index(), str));
        }

        return new StructureAnalysis(enrichedSwings, breaks, currentTrend,
                strongLow, strongHigh, weakLow, weakHigh);
    }
}
