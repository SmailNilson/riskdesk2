package com.riskdesk.domain.engine.backtest;

import com.riskdesk.domain.shared.vo.Timeframe;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Computes confirmed higher-timeframe support and resistance levels for entry filtering.
 */
public class HigherTimeframeLevelService {

    public enum LevelType { SUPPORT, RESISTANCE }
    public enum ThresholdMode { ATR, POINTS, TICKS }

    public record HtfLevel(
        LevelType type,
        BigDecimal price,
        Instant pivotTime,
        Instant confirmedAt,
        String timeframe
    ) {}

    public record LevelSelection(
        HtfLevel level,
        double distance,
        double threshold
    ) {}

    public record LevelIndex(
        List<HtfLevel> supports,
        List<HtfLevel> resistances
    ) {
        public static LevelIndex empty() {
            return new LevelIndex(List.of(), List.of());
        }
    }

    private final MarketStructureService marketStructureService;

    public HigherTimeframeLevelService(MarketStructureService marketStructureService) {
        this.marketStructureService = marketStructureService;
    }

    public LevelIndex buildLevelIndex(Map<String, List<com.riskdesk.domain.model.Candle>> candlesByTimeframe, int swingLength) {
        if (candlesByTimeframe.isEmpty()) {
            return LevelIndex.empty();
        }

        List<HtfLevel> supports = new ArrayList<>();
        List<HtfLevel> resistances = new ArrayList<>();

        for (Map.Entry<String, List<com.riskdesk.domain.model.Candle>> entry : candlesByTimeframe.entrySet()) {
            String timeframe = entry.getKey();
            List<MarketStructureService.ConfirmedSwing> swings =
                marketStructureService.detectConfirmedSwings(entry.getValue(), swingLength, timeframe);

            for (MarketStructureService.ConfirmedSwing swing : swings) {
                HtfLevel level = new HtfLevel(
                    swing.type() == com.riskdesk.domain.engine.smc.MarketStructure.SwingType.LOW
                        ? LevelType.SUPPORT
                        : LevelType.RESISTANCE,
                    swing.price(),
                    swing.pivotTime(),
                    swing.confirmedAt(),
                    timeframe
                );
                if (level.type() == LevelType.SUPPORT) {
                    supports.add(level);
                } else {
                    resistances.add(level);
                }
            }
        }

        supports.sort(Comparator.comparing(HtfLevel::confirmedAt).thenComparing(HtfLevel::price));
        resistances.sort(Comparator.comparing(HtfLevel::confirmedAt).thenComparing(HtfLevel::price));

        return new LevelIndex(List.copyOf(supports), List.copyOf(resistances));
    }

    public Optional<LevelSelection> nearestSupportAbove1H(
        LevelIndex index,
        Instant at,
        double close,
        ThresholdMode mode,
        double thresholdValue,
        double atrValue,
        double tickSize,
        int maxLevelAgeBars
    ) {
        return nearest(index.supports(), at, close, mode, thresholdValue, atrValue, tickSize, maxLevelAgeBars, true);
    }

    public Optional<LevelSelection> nearestResistanceAbove1H(
        LevelIndex index,
        Instant at,
        double close,
        ThresholdMode mode,
        double thresholdValue,
        double atrValue,
        double tickSize,
        int maxLevelAgeBars
    ) {
        return nearest(index.resistances(), at, close, mode, thresholdValue, atrValue, tickSize, maxLevelAgeBars, false);
    }

    public boolean isCloseToSupport(
        LevelIndex index,
        Instant at,
        double close,
        ThresholdMode mode,
        double thresholdValue,
        double atrValue,
        double tickSize,
        int maxLevelAgeBars
    ) {
        return nearestSupportAbove1H(index, at, close, mode, thresholdValue, atrValue, tickSize, maxLevelAgeBars).isPresent();
    }

    public boolean isCloseToResistance(
        LevelIndex index,
        Instant at,
        double close,
        ThresholdMode mode,
        double thresholdValue,
        double atrValue,
        double tickSize,
        int maxLevelAgeBars
    ) {
        return nearestResistanceAbove1H(index, at, close, mode, thresholdValue, atrValue, tickSize, maxLevelAgeBars).isPresent();
    }

    private Optional<LevelSelection> nearest(
        List<HtfLevel> levels,
        Instant at,
        double close,
        ThresholdMode mode,
        double thresholdValue,
        double atrValue,
        double tickSize,
        int maxLevelAgeBars,
        boolean support
    ) {
        double threshold = threshold(mode, thresholdValue, atrValue, tickSize);
        if (!Double.isFinite(threshold) || threshold < 0) {
            return Optional.empty();
        }

        LevelSelection best = null;
        for (HtfLevel level : levels) {
            if (level.confirmedAt().isAfter(at) || !isFreshEnough(level, at, maxLevelAgeBars)) {
                continue;
            }

            double levelPrice = level.price().doubleValue();
            double distance = support ? close - levelPrice : levelPrice - close;
            if (distance < 0 || distance > threshold) {
                continue;
            }

            if (best == null
                || distance < best.distance()
                || (distance == best.distance() && level.confirmedAt().isAfter(best.level().confirmedAt()))) {
                best = new LevelSelection(level, distance, threshold);
            }
        }

        return Optional.ofNullable(best);
    }

    private boolean isFreshEnough(HtfLevel level, Instant at, int maxLevelAgeBars) {
        if (maxLevelAgeBars <= 0) {
            return true;
        }
        Duration maxAge = Duration.ofMinutes((long) Timeframe.fromLabel(level.timeframe()).minutes() * maxLevelAgeBars);
        return !level.confirmedAt().isBefore(at.minus(maxAge));
    }

    private double threshold(ThresholdMode mode, double thresholdValue, double atrValue, double tickSize) {
        return switch (mode) {
            case ATR -> Double.isFinite(atrValue) ? atrValue * thresholdValue : Double.NaN;
            case POINTS -> thresholdValue;
            case TICKS -> tickSize * thresholdValue;
        };
    }
}
