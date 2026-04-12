package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.*;
import com.riskdesk.domain.orderflow.port.FlashCrashConfigPort;
import com.riskdesk.domain.orderflow.service.FlashCrashFSM;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Replays historical candles through the FlashCrashFSM to test thresholds (UC-OF-006).
 * Used for offline simulation / threshold tuning before deploying live detection.
 */
@Service
public class FlashCrashSimulationService {

    private final CandleRepositoryPort candleRepository;
    private final FlashCrashConfigPort flashCrashConfig;

    public FlashCrashSimulationService(CandleRepositoryPort candleRepository,
                                       FlashCrashConfigPort flashCrashConfig) {
        this.candleRepository = candleRepository;
        this.flashCrashConfig = flashCrashConfig;
    }

    /**
     * Result of a flash crash simulation run.
     */
    public record SimulationResult(
        Instrument instrument,
        String timeframe,
        Instant from,
        Instant to,
        int totalCandles,
        int crashEventsDetected,
        List<FlashCrashEvaluation> timeline
    ) {}

    /**
     * Replay historical candles through a fresh FlashCrashFSM instance.
     *
     * @param instrument the instrument to simulate
     * @param timeframe  candle timeframe (1m, 5m, 10m, etc.)
     * @param from       start of simulation window (inclusive)
     * @param to         end of simulation window (inclusive)
     * @param thresholds custom thresholds, or null to load persisted / defaults
     * @return simulation result with full timeline of FSM evaluations
     */
    public SimulationResult simulate(Instrument instrument, String timeframe,
                                     Instant from, Instant to,
                                     FlashCrashThresholds thresholds) {

        // Resolve thresholds: explicit > persisted > defaults
        FlashCrashThresholds effectiveThresholds = thresholds;
        if (effectiveThresholds == null) {
            effectiveThresholds = flashCrashConfig.loadThresholds(instrument)
                    .orElse(FlashCrashThresholds.defaults());
        }

        // Load candles — CandleRepositoryPort.findCandles returns candles from 'from' onward.
        // We filter to the [from, to] range in code.
        List<Candle> allCandles = candleRepository.findCandles(instrument, timeframe, from);
        List<Candle> candles = allCandles.stream()
                .filter(c -> !c.getTimestamp().isBefore(from) && !c.getTimestamp().isAfter(to))
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();

        if (candles.isEmpty()) {
            return new SimulationResult(instrument, timeframe, from, to, 0, 0, List.of());
        }

        // Derive candle duration in seconds from timeframe string
        long candleDurationSeconds = parseCandleDurationSeconds(timeframe);
        double tickSize = instrument.getTickSize().doubleValue();

        // Create fresh FSM for this simulation
        FlashCrashFSM fsm = new FlashCrashFSM();
        List<FlashCrashEvaluation> timeline = new ArrayList<>();
        int crashEvents = 0;

        // Rolling average volume over 20 candles
        Deque<Long> volumeWindow = new ArrayDeque<>(20);
        double previousVelocity = 0.0;

        for (Candle candle : candles) {
            double open = candle.getOpen().doubleValue();
            double high = candle.getHigh().doubleValue();
            double low = candle.getLow().doubleValue();
            double close = candle.getClose().doubleValue();
            long volume = candle.getVolume();

            // Velocity: |close - open| / duration / tickSize (ticks per second)
            double velocity = 0.0;
            if (candleDurationSeconds > 0 && tickSize > 0) {
                velocity = Math.abs(close - open) / candleDurationSeconds / tickSize;
            }

            // Delta5s estimate from CLV: ((close-low) - (high-close)) / (high-low) * volume
            double delta5s = 0.0;
            double range = high - low;
            if (range > 0) {
                delta5s = ((close - low) - (high - close)) / range * volume;
            }

            // Acceleration ratio: currentVelocity / previousVelocity (1.0 for first candle)
            double accelerationRatio = 1.0;
            if (previousVelocity > 0) {
                accelerationRatio = velocity / previousVelocity;
            }

            // Depth imbalance: not available historically, use neutral 0.5
            double depthImbalance = 0.5;

            // Volume spike ratio: volume / rolling average volume (20 candles)
            volumeWindow.addLast(volume);
            if (volumeWindow.size() > 20) {
                volumeWindow.removeFirst();
            }
            double avgVolume = volumeWindow.stream().mapToLong(Long::longValue).average().orElse(1.0);
            double volumeSpikeRatio = avgVolume > 0 ? volume / avgVolume : 1.0;

            FlashCrashInput input = new FlashCrashInput(
                    velocity,
                    delta5s,
                    accelerationRatio,
                    depthImbalance,
                    volumeSpikeRatio,
                    candle.getTimestamp()
            );

            FlashCrashEvaluation eval = fsm.evaluate(input, effectiveThresholds);
            timeline.add(eval);

            // Count transitions away from NORMAL as crash events
            if (eval.phaseChanged() && eval.previousPhase() == CrashPhase.NORMAL
                    && eval.currentPhase() != CrashPhase.NORMAL) {
                crashEvents++;
            }

            previousVelocity = velocity;
        }

        return new SimulationResult(
                instrument, timeframe, from, to,
                candles.size(), crashEvents, timeline
        );
    }

    /**
     * Parse timeframe string to candle duration in seconds.
     */
    private static long parseCandleDurationSeconds(String timeframe) {
        return switch (timeframe.toLowerCase()) {
            case "1m" -> 60;
            case "5m" -> 300;
            case "10m" -> 600;
            case "15m" -> 900;
            case "30m" -> 1800;
            case "1h" -> 3600;
            case "4h" -> 14400;
            case "1d" -> 86400;
            default -> 60; // fallback to 1 minute
        };
    }
}
