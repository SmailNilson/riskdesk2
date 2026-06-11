package com.riskdesk.application.quant.backtest;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.backtest.QuantExitReplayEngine;
import com.riskdesk.domain.quant.backtest.QuantExitReplayParams;
import com.riskdesk.domain.quant.backtest.QuantExitReplayResult;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;
import com.riskdesk.domain.quant.simulation.port.Quant7GatesSimulationRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Runs an exit-policy replay over the RECORDED Quant 7-Gates simulations —
 * the persisted order-flow entry signals are re-managed under a parameterised
 * exit/filters bundle against historical 1m candles.
 *
 * <p>This answers "what would the recorded signals have earned under policy X"
 * repeatably as history accumulates, without touching the live harness. It is
 * an ENTRY-replay (recorded signals), not a gate-replay: it cannot discover
 * entries the live evaluator never fired.</p>
 */
@Service
public class Quant7GatesExitBacktestService {

    private static final Logger log = LoggerFactory.getLogger(Quant7GatesExitBacktestService.class);

    /**
     * Candle warm-up loaded before the first entry so the hourly EMA slow leg
     * and the 5m ATR are stable at the first replayed trade.
     */
    private static final Duration WARMUP = Duration.ofDays(7);

    private final Quant7GatesSimulationRepositoryPort simulationRepository;
    private final CandleRepositoryPort candlePort;

    public Quant7GatesExitBacktestService(Quant7GatesSimulationRepositoryPort simulationRepository,
                                          CandleRepositoryPort candlePort) {
        this.simulationRepository = simulationRepository;
        this.candlePort = candlePort;
    }

    /**
     * @param instrumentFilter limit to one instrument, or {@code null} for all
     * @param from             inclusive lower bound on {@code openedAt}, or null
     * @param to               inclusive upper bound on {@code openedAt}, or null
     */
    public QuantExitReplayResult run(Instrument instrumentFilter,
                                     Instant from,
                                     Instant to,
                                     QuantExitReplayParams params) {
        List<Quant7GatesSimulation> recorded = new ArrayList<>();
        for (Quant7GatesSimulation sim : simulationRepository.findAllClosed()) {
            if (instrumentFilter != null && sim.instrument() != instrumentFilter) continue;
            if (from != null && sim.openedAt().isBefore(from)) continue;
            if (to != null && sim.openedAt().isAfter(to)) continue;
            recorded.add(sim);
        }
        recorded.sort(Comparator.comparing(Quant7GatesSimulation::openedAt));

        Map<Instrument, List<Candle>> candles = new EnumMap<>(Instrument.class);
        for (Instrument instrument : recorded.stream().map(Quant7GatesSimulation::instrument).distinct().toList()) {
            Instant first = recorded.stream()
                .filter(s -> s.instrument() == instrument)
                .map(Quant7GatesSimulation::openedAt)
                .min(Comparator.naturalOrder())
                .orElseThrow();
            Instant last = recorded.stream()
                .filter(s -> s.instrument() == instrument)
                .map(s -> s.closedAt() != null ? s.closedAt() : s.openedAt())
                .max(Comparator.naturalOrder())
                .orElseThrow();
            // Window: warm-up before the first entry; +2 days after the last
            // recorded close so replayed trades that outlive their recorded
            // twin can still resolve at SL/TP/EOD.
            List<Candle> series = candlePort.findCandlesBetween(
                instrument, "1m", first.minus(WARMUP), last.plus(Duration.ofDays(2)));
            candles.put(instrument, series);
            log.info("quant-exit-backtest loaded instrument={} recordedTrades={} candles1m={}",
                instrument, recorded.stream().filter(s -> s.instrument() == instrument).count(), series.size());
        }

        return QuantExitReplayEngine.replay(recorded, candles, params);
    }
}
