package com.riskdesk.domain.engine.backtest;

import com.riskdesk.domain.engine.smc.MarketStructure;
import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sequential, non-repainting market structure service used by the backtest layer.
 * <p>
 * It converts confirmed swing highs/lows into structure events (BOS / CHOCH)
 * without leaking future bars into the decision time.
 */
public class MarketStructureService {

    public record ConfirmedSwing(
        MarketStructure.SwingType type,
        BigDecimal price,
        int pivotIndex,
        Instant pivotTime,
        Instant confirmedAt,
        String timeframe
    ) {}

    public record StructureEvent(
        MarketStructure.StructureType type,
        MarketStructure.Trend newTrend,
        BigDecimal breakLevel,
        int breakIndex,
        Instant breakTime,
        ConfirmedSwing brokenSwing,
        String timeframe
    ) {}

    public record StructureContext(
        MarketStructure.Trend trend,
        StructureEvent lastEvent
    ) {
        public static StructureContext empty() {
            return new StructureContext(MarketStructure.Trend.UNDEFINED, null);
        }

        public boolean bullishConfirmation(boolean useBos, boolean useChoch) {
            if (trend != MarketStructure.Trend.BULLISH) {
                return false;
            }
            if (lastEvent == null) {
                return !useBos && !useChoch;
            }
            return (useBos && lastEvent.type() == MarketStructure.StructureType.BOS && lastEvent.newTrend() == MarketStructure.Trend.BULLISH)
                || (useChoch && lastEvent.type() == MarketStructure.StructureType.CHOCH && lastEvent.newTrend() == MarketStructure.Trend.BULLISH)
                || (!useBos && !useChoch);
        }

        public boolean bearishConfirmation(boolean useBos, boolean useChoch) {
            if (trend != MarketStructure.Trend.BEARISH) {
                return false;
            }
            if (lastEvent == null) {
                return !useBos && !useChoch;
            }
            return (useBos && lastEvent.type() == MarketStructure.StructureType.BOS && lastEvent.newTrend() == MarketStructure.Trend.BEARISH)
                || (useChoch && lastEvent.type() == MarketStructure.StructureType.CHOCH && lastEvent.newTrend() == MarketStructure.Trend.BEARISH)
                || (!useBos && !useChoch);
        }
    }

    public record StructureContextIndex(
        List<StructureEvent> events
    ) {
        public static StructureContextIndex empty() {
            return new StructureContextIndex(List.of());
        }

        public StructureContext contextAt(Instant time) {
            StructureEvent last = null;
            for (StructureEvent event : events) {
                if (event.breakTime().isAfter(time)) {
                    break;
                }
                last = event;
            }
            return last == null
                ? StructureContext.empty()
                : new StructureContext(last.newTrend(), last);
        }
    }

    public List<ConfirmedSwing> detectConfirmedSwings(List<Candle> candles, int swingLength, String timeframe) {
        if (candles.size() < swingLength * 2 + 1) {
            return List.of();
        }

        List<ConfirmedSwing> swings = new ArrayList<>();
        for (int i = swingLength; i < candles.size() - swingLength; i++) {
            if (isSwingHigh(candles, i, swingLength)) {
                swings.add(new ConfirmedSwing(
                    MarketStructure.SwingType.HIGH,
                    candles.get(i).getHigh(),
                    i,
                    candles.get(i).getTimestamp(),
                    candles.get(i + swingLength).getTimestamp(),
                    timeframe
                ));
            }
            if (isSwingLow(candles, i, swingLength)) {
                swings.add(new ConfirmedSwing(
                    MarketStructure.SwingType.LOW,
                    candles.get(i).getLow(),
                    i,
                    candles.get(i).getTimestamp(),
                    candles.get(i + swingLength).getTimestamp(),
                    timeframe
                ));
            }
        }

        swings.sort(Comparator
            .comparing(ConfirmedSwing::confirmedAt)
            .thenComparingInt(ConfirmedSwing::pivotIndex));
        return List.copyOf(swings);
    }

    public StructureContextIndex buildStructureContextIndex(List<Candle> candles, int swingLength, String timeframe) {
        List<ConfirmedSwing> confirmedSwings = detectConfirmedSwings(candles, swingLength, timeframe);
        if (confirmedSwings.isEmpty()) {
            return StructureContextIndex.empty();
        }

        List<StructureEvent> events = new ArrayList<>();
        MarketStructure.Trend trend = MarketStructure.Trend.UNDEFINED;
        ConfirmedSwing activeHigh = null;
        ConfirmedSwing activeLow = null;
        boolean activeHighBroken = false;
        boolean activeLowBroken = false;
        int swingPointer = 0;

        for (int candleIndex = 0; candleIndex < candles.size(); candleIndex++) {
            Candle candle = candles.get(candleIndex);

            while (swingPointer < confirmedSwings.size()
                && !confirmedSwings.get(swingPointer).confirmedAt().isAfter(candle.getTimestamp())) {
                ConfirmedSwing swing = confirmedSwings.get(swingPointer++);
                if (swing.type() == MarketStructure.SwingType.HIGH) {
                    activeHigh = swing;
                    activeHighBroken = false;
                } else {
                    activeLow = swing;
                    activeLowBroken = false;
                }
            }

            if (activeHigh != null && !activeHighBroken
                && candle.getClose().compareTo(activeHigh.price()) > 0) {
                MarketStructure.StructureType type =
                    (trend == MarketStructure.Trend.BEARISH || trend == MarketStructure.Trend.UNDEFINED)
                        ? MarketStructure.StructureType.CHOCH
                        : MarketStructure.StructureType.BOS;
                trend = MarketStructure.Trend.BULLISH;
                activeHighBroken = true;
                events.add(new StructureEvent(
                    type,
                    trend,
                    activeHigh.price(),
                    candleIndex,
                    candle.getTimestamp(),
                    activeHigh,
                    timeframe
                ));
            }

            if (activeLow != null && !activeLowBroken
                && candle.getClose().compareTo(activeLow.price()) < 0) {
                MarketStructure.StructureType type =
                    (trend == MarketStructure.Trend.BULLISH || trend == MarketStructure.Trend.UNDEFINED)
                        ? MarketStructure.StructureType.CHOCH
                        : MarketStructure.StructureType.BOS;
                trend = MarketStructure.Trend.BEARISH;
                activeLowBroken = true;
                events.add(new StructureEvent(
                    type,
                    trend,
                    activeLow.price(),
                    candleIndex,
                    candle.getTimestamp(),
                    activeLow,
                    timeframe
                ));
            }
        }

        return new StructureContextIndex(List.copyOf(events));
    }

    private boolean isSwingHigh(List<Candle> candles, int index, int swingLength) {
        BigDecimal high = candles.get(index).getHigh();
        for (int i = index - swingLength; i <= index + swingLength; i++) {
            if (i == index) {
                continue;
            }
            if (candles.get(i).getHigh().compareTo(high) >= 0) {
                return false;
            }
        }
        return true;
    }

    private boolean isSwingLow(List<Candle> candles, int index, int swingLength) {
        BigDecimal low = candles.get(index).getLow();
        for (int i = index - swingLength; i <= index + swingLength; i++) {
            if (i == index) {
                continue;
            }
            if (candles.get(i).getLow().compareTo(low) <= 0) {
                return false;
            }
        }
        return true;
    }
}
