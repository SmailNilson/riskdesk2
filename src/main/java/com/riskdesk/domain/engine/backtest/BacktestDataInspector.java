package com.riskdesk.domain.engine.backtest;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BacktestDataInspector {

    private BacktestDataInspector() {}

    public record InspectionResult(
        List<Candle> candles,
        BacktestResult.DataAudit audit,
        Instant effectiveEvaluationStart
    ) {}

    public static InspectionResult inspect(
        Instrument instrument,
        String timeframe,
        List<Candle> rawCandles,
        Instant evaluationStart,
        int requestedWarmupBars
    ) {
        int outOfOrderPairs = 0;
        for (int i = 1; i < rawCandles.size(); i++) {
            if (rawCandles.get(i).getTimestamp().isBefore(rawCandles.get(i - 1).getTimestamp())) {
                outOfOrderPairs++;
            }
        }

        List<Candle> sorted = rawCandles.stream()
            .sorted(Comparator.comparing(Candle::getTimestamp))
            .toList();

        Map<Instant, Candle> deduped = new LinkedHashMap<>();
        int duplicateCandles = 0;
        for (Candle candle : sorted) {
            Candle previous = deduped.put(candle.getTimestamp(), candle);
            if (previous != null) {
                duplicateCandles++;
            }
        }

        List<Candle> candles = new ArrayList<>(deduped.values());
        Duration barDuration = parseTimeframe(timeframe);
        int trimmedTrailingCandles = trimTrailingSuspiciousTail(instrument, barDuration, candles);
        Instant requestedEvaluationStart = evaluationStart;
        int requestedStartIndex = resolveEvaluationStartIndex(candles, requestedEvaluationStart);
        Instant effectiveEvaluationStart = requestedEvaluationStart == null
            ? (candles.isEmpty() ? null : candles.get(0).getTimestamp())
            : requestedEvaluationStart;
        boolean adjustedEvaluationStart = false;

        if (requestedEvaluationStart != null
            && requestedWarmupBars > 0
            && requestedStartIndex < requestedWarmupBars
            && candles.size() > requestedWarmupBars) {
            effectiveEvaluationStart = candles.get(requestedWarmupBars).getTimestamp();
            adjustedEvaluationStart = true;
        }

        int alignedCandles = 0;
        int misalignedCandles = 0;
        int gapCount = 0;
        int suspiciousGapCount = 0;
        Duration maxGap = Duration.ZERO;

        for (int i = 0; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            if (isAligned(instrument, candle.getTimestamp(), barDuration)) {
                alignedCandles++;
            } else {
                misalignedCandles++;
            }

            if (i == 0) {
                continue;
            }

            Duration gap = Duration.between(candles.get(i - 1).getTimestamp(), candle.getTimestamp());
            if (!gap.equals(barDuration)) {
                gapCount++;
                if (!isExpectedGap(instrument, barDuration, gap, candles.get(i - 1).getTimestamp(), candle.getTimestamp())) {
                    suspiciousGapCount++;
                }
                if (gap.compareTo(maxGap) > 0) {
                    maxGap = gap;
                }
            }
        }

        int availableWarmupBars = 0;
        int evaluatedCandles = 0;
        for (Candle candle : candles) {
            if (effectiveEvaluationStart == null || candle.getTimestamp().isBefore(effectiveEvaluationStart)) {
                availableWarmupBars++;
            } else {
                evaluatedCandles++;
            }
        }

        boolean sufficientWarmup = requestedWarmupBars <= 0 || availableWarmupBars >= requestedWarmupBars;
        List<String> warnings = new ArrayList<>();
        if (duplicateCandles > 0) warnings.add("Duplicate candle timestamps were removed before backtest execution.");
        if (outOfOrderPairs > 0) warnings.add("Source candles were out of order and were sorted before execution.");
        if (trimmedTrailingCandles > 0) warnings.add("Removed " + trimmedTrailingCandles
            + " trailing candle(s) after a suspicious gap to keep the backtest series contiguous.");
        if (misalignedCandles > 0) warnings.add("Some candles are not aligned to the declared timeframe boundary.");
        if (suspiciousGapCount > 0) warnings.add("Suspicious gaps were detected inside the candle series.");
        if (adjustedEvaluationStart && requestedEvaluationStart != null) {
            warnings.add("Evaluation start was shifted to " + effectiveEvaluationStart + " to preserve "
                + requestedWarmupBars + " warmup bars on the available data.");
        }
        if (!sufficientWarmup) warnings.add("Requested warmup coverage is not fully available before the evaluation start.");

        BacktestResult.DataAudit audit = new BacktestResult.DataAudit(
            candles.size(),
            evaluatedCandles,
            duplicateCandles,
            outOfOrderPairs,
            alignedCandles,
            misalignedCandles,
            gapCount,
            suspiciousGapCount,
            maxGap.isZero() ? null : maxGap.toString(),
            candles.isEmpty() ? null : candles.get(0).getTimestamp().toString(),
            requestedEvaluationStart == null ? null : requestedEvaluationStart.toString(),
            effectiveEvaluationStart == null ? null : effectiveEvaluationStart.toString(),
            candles.isEmpty() ? null : candles.get(candles.size() - 1).getTimestamp().toString(),
            "UTC",
            requestedWarmupBars,
            availableWarmupBars,
            sufficientWarmup,
            adjustedEvaluationStart,
            warnings
        );

        return new InspectionResult(candles, audit, effectiveEvaluationStart);
    }

    public static Duration parseTimeframe(String timeframe) {
        return switch (timeframe) {
            case "1m" -> Duration.ofMinutes(1);
            case "5m" -> Duration.ofMinutes(5);
            case "10m" -> Duration.ofMinutes(10);
            case "15m" -> Duration.ofMinutes(15);
            case "30m" -> Duration.ofMinutes(30);
            case "1h" -> Duration.ofHours(1);
            case "4h" -> Duration.ofHours(4);
            case "1d" -> Duration.ofDays(1);
            default -> Duration.ZERO;
        };
    }

    public static Instant floorToTimeframe(Instant instant, String timeframe) {
        Duration duration = parseTimeframe(timeframe);
        if (duration.isZero()) {
            return instant;
        }

        long seconds = duration.getSeconds();
        long epochSecond = instant.getEpochSecond();
        long floored = (epochSecond / seconds) * seconds;
        return Instant.ofEpochSecond(floored);
    }

    private static int resolveEvaluationStartIndex(List<Candle> candles, Instant evaluationStart) {
        if (evaluationStart == null) {
            return 0;
        }
        for (int i = 0; i < candles.size(); i++) {
            if (!candles.get(i).getTimestamp().isBefore(evaluationStart)) {
                return i;
            }
        }
        return candles.size();
    }

    private static int trimTrailingSuspiciousTail(Instrument instrument, Duration barDuration, List<Candle> candles) {
        if (candles.size() < 2 || barDuration.isZero()) {
            return 0;
        }

        final int maxTrailingContaminatedCandles = 4;
        int trimFromIndex = -1;

        for (int i = 1; i < candles.size(); i++) {
            Duration gap = Duration.between(candles.get(i - 1).getTimestamp(), candles.get(i).getTimestamp());
            if (gap.equals(barDuration) || isExpectedGap(instrument, barDuration, gap, candles.get(i - 1).getTimestamp(), candles.get(i).getTimestamp())) {
                continue;
            }

            Duration suspiciousTailThreshold = barDuration.multipliedBy(3);
            if (gap.compareTo(suspiciousTailThreshold) < 0) {
                continue;
            }

            int trailingCandles = candles.size() - i;
            if (trailingCandles <= maxTrailingContaminatedCandles) {
                trimFromIndex = i;
            }
        }

        if (trimFromIndex <= 0) {
            return 0;
        }

        int removed = candles.size() - trimFromIndex;
        candles.subList(trimFromIndex, candles.size()).clear();
        return removed;
    }

    private static boolean isAligned(Instrument instrument, Instant instant, Duration duration) {
        if (duration.isZero()) {
            return true;
        }
        if (isSupportedFutures(instrument) && duration.equals(Duration.ofHours(1))) {
            int minute = instant.atZone(ZoneOffset.UTC).getMinute();
            return minute == 0 || minute == 30;
        }
        long seconds = duration.getSeconds();
        return instant.getEpochSecond() % seconds == 0;
    }

    private static boolean isExpectedGap(
        Instrument instrument,
        Duration barDuration,
        Duration gap,
        Instant previous,
        Instant current
    ) {
        if (gap.equals(barDuration)) {
            return true;
        }

        if (instrument == Instrument.MNQ || instrument == Instrument.MCL || instrument == Instrument.MGC || instrument == Instrument.E6) {
            if (barDuration.equals(Duration.ofHours(1)) && gap.equals(Duration.ofMinutes(30))) {
                ZonedDateTime prevUtc = previous.atZone(ZoneOffset.UTC);
                ZonedDateTime currUtc = current.atZone(ZoneOffset.UTC);
                if (prevUtc.getMinute() == 30 && currUtc.getMinute() == 0) {
                    return true;
                }
            }

            Duration maintenanceGap = barDuration.plus(Duration.ofHours(1));
            if (gap.equals(maintenanceGap)) {
                return true;
            }

            if (gap.compareTo(Duration.ofHours(17)) >= 0 && gap.compareTo(Duration.ofHours(18)) <= 0) {
                return true;
            }

            ZonedDateTime prevUtc = previous.atZone(ZoneOffset.UTC);
            ZonedDateTime currUtc = current.atZone(ZoneOffset.UTC);
            if ((currUtc.getDayOfWeek().getValue() == 7 || currUtc.getDayOfWeek().getValue() == 1)
                && gap.compareTo(Duration.ofDays(2)) >= 0
                && gap.compareTo(Duration.ofDays(4).plusHours(18)) <= 0) {
                return true;
            }

            if (gap.compareTo(Duration.ofDays(1)) >= 0 && gap.compareTo(Duration.ofDays(1).plusHours(6)) <= 0) {
                // Holiday-shortened sessions can produce larger than normal maintenance gaps.
                return true;
            }
        }

        return false;
    }

    private static boolean isSupportedFutures(Instrument instrument) {
        return instrument == Instrument.MNQ
            || instrument == Instrument.MCL
            || instrument == Instrument.MGC
            || instrument == Instrument.E6;
    }
}
