package com.riskdesk.application.service.strategy;

import com.riskdesk.application.service.PlaybookService;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.engine.playbook.model.PlaybookPlan;
import com.riskdesk.domain.engine.strategy.playbook.*;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookTrailingExitEvaluator.Decision;
import com.riskdesk.domain.engine.strategy.playbook.port.PlaybookSignalHistoryPort;
import com.riskdesk.domain.engine.strategy.playbook.port.PlaybookStrategyStatePort;
import com.riskdesk.domain.engine.strategy.wtx.WtxPosition;
import com.riskdesk.domain.engine.strategy.wtx.WtxRiskGuard;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingResult;
import com.riskdesk.domain.marketdata.event.CandleClosed;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.domain.simulation.ReviewType;
import com.riskdesk.domain.simulation.TradeSimulation;
import com.riskdesk.domain.simulation.port.TradeSimulationRepositoryPort;
import com.riskdesk.infrastructure.config.PlaybookStrategyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Orchestrates background Playbook scanning, risk-guard checking, execution routing, and local simulations.
 * Modeled closely after the premium WaveTrend XT (WTX) engine architecture.
 */
@Service
@ConditionalOnProperty(name = "riskdesk.playbook.enabled", havingValue = "true")
public class PlaybookStrategyService {

    private static final Logger log = LoggerFactory.getLogger(PlaybookStrategyService.class);

    private final PlaybookStrategyStatePort statePort;
    private final PlaybookSignalHistoryPort historyPort;
    private final PlaybookService playbookService;
    private final CandleRepositoryPort candlePort;
    private final PlaybookExecutionBridge executionBridge;
    private final TradeSimulationRepositoryPort simulationRepository;
    private final SimpMessagingTemplate ws;
    private final PlaybookStrategyProperties properties;

    @Autowired
    public PlaybookStrategyService(
            PlaybookStrategyStatePort statePort,
            PlaybookSignalHistoryPort historyPort,
            PlaybookService playbookService,
            CandleRepositoryPort candlePort,
            PlaybookExecutionBridge executionBridge,
            TradeSimulationRepositoryPort simulationRepository,
            SimpMessagingTemplate ws,
            PlaybookStrategyProperties properties
    ) {
        this.statePort = statePort;
        this.historyPort = historyPort;
        this.playbookService = playbookService;
        this.candlePort = candlePort;
        this.executionBridge = executionBridge;
        this.simulationRepository = simulationRepository;
        this.ws = ws;
        this.properties = properties;
    }

    /**
     * Listens to CandleClosed events to trigger autonomous scans and state updates.
     */
    @EventListener
    public void onCandleClosed(CandleClosed event) {
        if (!properties.isEnabled()) return;
        if (!properties.getInstruments().contains(event.instrument())) return;
        if (!properties.getTimeframes().contains(event.timeframe())) return;

        String instrumentName = event.instrument();
        Instrument instrument;
        try {
            instrument = Instrument.valueOf(instrumentName);
        } catch (IllegalArgumentException e) {
            log.error("Playbook Strategy skip — Unknown instrument: {}", instrumentName);
            return;
        }

        PlaybookStrategyState state = statePort.load(instrumentName, event.timeframe())
                .orElseGet(() -> PlaybookStrategyState.initial(instrumentName, event.timeframe(),
                        properties.getInitialEquity()));

        // Check for new trading day (reset realized P&L and start equity)
        if (WtxRiskGuard.isNewTradingDay(state.lastCandleTs(), event.timestamp())) {
            BigDecimal equity = state.currentEquity();
            state = state.withDayReset(equity);
            log.info("Playbook [{} {}] new trading day — equity reset to {}", instrumentName, event.timeframe(), equity);
        }

        PlaybookProfile profile = state.activeProfile() != null ? state.activeProfile() : PlaybookProfile.BASELINE;

        // Fetch the most recent candle for pricing / evaluation
        List<Candle> candles = candlePort.findRecentCandles(instrument, event.timeframe(), 1);
        if (candles.isEmpty()) return;
        Candle currentCandle = candles.get(0);

        // ── 1. Exits evaluation for live active position ──────────────────────
        if (state.currentPosition() != WtxPosition.FLAT && state.autoExecutionEnabled()) {
            List<PlaybookSignal> recent = historyPort.findRecent(instrumentName, event.timeframe(), 1);
            if (!recent.isEmpty()) {
                PlaybookSignal entrySignal = recent.get(0);
                Decision exit = PlaybookTrailingExitEvaluator.evaluate(
                        state, currentCandle, entrySignal.stopLoss(), entrySignal.takeProfit1()
                );
                if (exit.shouldExit()) {
                    state = applyExit(state, instrument, exit, event, profile);
                } else {
                    state = state.withTrailing(exit.updatedBestFavorablePrice(), exit.updatedTrailingStopPrice());
                }
            }
        }

        // ── 2. Daily Max-Loss Guard (only for SESSION_ATR or STRICT profiles) ──
        if (profile.blocksOnMaxLoss() && !state.maxLossHit()) {
            BigDecimal maxLoss = properties.getMaxDailyLossUsd();
            if (state.dailyRealizedPnl().compareTo(maxLoss.negate()) <= 0) {
                log.warn("Playbook [{} {}] daily max-loss limit hit ({}). Disabling entries.",
                        instrumentName, event.timeframe(), state.dailyRealizedPnl());
                if (state.currentPosition() != WtxPosition.FLAT) {
                    if (state.autoExecutionEnabled()) {
                        // Flatten open live position
                        WtxRoutingResult closeResult = executionBridge.submitClose(state, currentCandle.getClose());
                        log.info("Playbook [{} {}] Live close on max loss hit, result={}",
                                instrumentName, event.timeframe(), closeResult.outcome());
                    }
                    state = closePosition(state, instrument, currentCandle.getClose());
                }
                state = state.withMaxLossHit();
            }
        }

        // ── 3. Scan & Entry evaluation ─────────────────────────────────────────
        if (state.currentPosition() == WtxPosition.FLAT && (!profile.blocksOnMaxLoss() || !state.maxLossHit())) {
            PlaybookEvaluation evaluation = playbookService.evaluate(instrument, event.timeframe());
            if (evaluation != null && evaluation.checklistScore() >= profile.minScore() && evaluation.plan() != null) {
                triggerEntry(evaluation, state, instrument, event);
                // Reload state since triggerEntry modifies/saves it
                state = statePort.load(instrumentName, event.timeframe()).orElse(state);
            }
        }

        // Update last candle ts and save
        state = state.withLastCandleTs(event.timestamp());
        statePort.save(state);
        publishState(state);
    }

    /**
     * Trigger entry: creates signal history, routes live IBKR orders if auto-execution is on,
     * or spawns a virtual simulation if auto-execution is off.
     */
    private void triggerEntry(PlaybookEvaluation evaluation, PlaybookStrategyState state, Instrument instrument, CandleClosed event) {
        UUID signalId = UUID.randomUUID();
        PlaybookPlan plan = evaluation.plan();

        String direction = "LONG";
        if (evaluation.bestSetup() != null) {
            String name = evaluation.bestSetup().type().name();
            if (name.contains("SHORT") || name.contains("BEARISH") || (evaluation.bestSetup().rrRatio() < 0)) {
                direction = "SHORT";
            }
        } else if (plan.stopLoss().compareTo(plan.entryPrice()) > 0) {
            direction = "SHORT";
        }

        PlaybookSignal signal = new PlaybookSignal(
                signalId,
                state.instrument(),
                state.timeframe(),
                event.timestamp(),
                direction,
                evaluation.checklistScore(),
                evaluation.bestSetup() != null ? evaluation.bestSetup().type().name() : "ZONE_RETEST",
                plan.entryPrice(),
                plan.stopLoss(),
                plan.takeProfit1(),
                plan.takeProfit2(),
                null,
                null
        );

        BigDecimal atr = playbookService.computeAtr(instrument, event.timeframe());

        if (state.autoExecutionEnabled()) {
            log.info("Playbook [{} {}] QUALIFIED setup score {}/7 — Routing Live LIMIT order",
                    state.instrument(), state.timeframe(), evaluation.checklistScore());
            WtxRoutingResult result = executionBridge.submitEntry(signal, state);
            signal = signal.withRouting(result);
            historyPort.save(signal);

            WtxPosition entryPos = "LONG".equalsIgnoreCase(direction) ? WtxPosition.LONG : WtxPosition.SHORT;
            state = state.withPosition(entryPos, plan.entryPrice(), BigDecimal.valueOf(state.configuredOrderQty()), atr);
            statePort.save(state);
        } else {
            log.info("Playbook [{} {}] QUALIFIED setup score {}/7 — Spawning virtual TradeSimulation",
                    state.instrument(), state.timeframe(), evaluation.checklistScore());
            historyPort.save(signal);

            TradeSimulation sim = new TradeSimulation(
                    null,
                    signal.id().getMostSignificantBits(),
                    ReviewType.PLAYBOOK,
                    state.instrument(),
                    direction,
                    TradeSimulationStatus.PENDING_ENTRY,
                    null,
                    null,
                    BigDecimal.ZERO,
                    null,
                    null,
                    null,
                    Instant.now()
            );
            TradeSimulation savedSim = simulationRepository.save(sim);
            publishSimulationEvent(savedSim);

            WtxPosition entryPos = "LONG".equalsIgnoreCase(direction) ? WtxPosition.LONG : WtxPosition.SHORT;
            state = state.withPosition(entryPos, plan.entryPrice(), BigDecimal.valueOf(state.configuredOrderQty()), atr);
            statePort.save(state);
        }

        ws.convertAndSend("/topic/playbook-signals", toWsPayload(signal, state));
    }

    /**
     * Callback for TradeSimulationService to update position state to FLAT when a virtual simulation resolves.
     */
    public void handleVirtualExit(PlaybookSignal signal, TradeSimulationStatus status, BigDecimal exitPrice) {
        if (signal == null) return;
        PlaybookStrategyState state = statePort.load(signal.instrument(), signal.timeframe()).orElse(null);
        if (state == null || state.currentPosition() == WtxPosition.FLAT) return;

        Instrument instrument;
        try {
            instrument = Instrument.valueOf(signal.instrument());
        } catch (IllegalArgumentException e) {
            return;
        }

        log.info("Playbook [{} {}] virtual trade resolved to {} @ {}. Flattening state.",
                signal.instrument(), signal.timeframe(), status, exitPrice);

        state = closePosition(state, instrument, exitPrice);
        statePort.save(state);

        publishState(state);
    }

    private PlaybookStrategyState applyExit(PlaybookStrategyState state, Instrument instrument,
                                            Decision exit, CandleClosed event, PlaybookProfile profile) {
        log.info("Playbook [{} {}] trailing exit fired: reason={}, exitPrice={}",
                state.instrument(), state.timeframe(), exit.reason(), exit.exitPrice());

        PlaybookSignal exitSignal = new PlaybookSignal(
                UUID.randomUUID(),
                state.instrument(),
                state.timeframe(),
                event.timestamp(),
                state.currentPosition() == WtxPosition.LONG ? "SHORT" : "LONG",
                0,
                "EXIT_" + exit.reason().name(),
                exit.exitPrice(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                null
        );

        WtxRoutingResult closeResult = executionBridge.submitClose(state, exit.exitPrice());
        exitSignal = exitSignal.withRouting(closeResult);
        historyPort.save(exitSignal);

        state = closePosition(state, instrument, exit.exitPrice());
        statePort.save(state);

        ws.convertAndSend("/topic/playbook-signals", toWsPayload(exitSignal, state));
        return state;
    }

    private PlaybookStrategyState closePosition(PlaybookStrategyState state, Instrument instrument, BigDecimal exitPrice) {
        if (state.currentPosition() == WtxPosition.FLAT || state.entryPrice() == null) {
            return state;
        }
        Side side = state.currentPosition() == WtxPosition.LONG ? Side.LONG : Side.SHORT;
        int qty = state.entryQty() != null ? state.entryQty().intValue() : 1;
        BigDecimal pnl = instrument.calculatePnL(state.entryPrice(), exitPrice, qty, side);
        return state.withFlat(pnl);
    }

    // ── Profile and Parameter Controls ─────────────────────────────────────

    public PlaybookStrategyState updateProfile(String instrument, String timeframe, PlaybookProfile profile) {
        PlaybookStrategyState state = statePort.load(instrument, timeframe)
                .orElseGet(() -> PlaybookStrategyState.initial(instrument, timeframe, properties.getInitialEquity()));
        PlaybookStrategyState updated = state.withProfile(profile);
        statePort.save(updated);
        log.info("Playbook [{} {}] profile updated to {}", instrument, timeframe, profile);
        publishState(updated);
        return updated;
    }

    public PlaybookStrategyState updateAutoExecution(String instrument, String timeframe, boolean enabled) {
        PlaybookStrategyState state = statePort.load(instrument, timeframe)
                .orElseGet(() -> PlaybookStrategyState.initial(instrument, timeframe, properties.getInitialEquity()));
        PlaybookStrategyState updated = state.withAutoExecution(enabled);
        statePort.save(updated);
        log.info("Playbook [{} {}] auto-execution updated to {}", instrument, timeframe, enabled);
        publishState(updated);
        return updated;
    }

    public PlaybookStrategyState updateConfiguredOrderQty(String instrument, String timeframe, int qty) {
        PlaybookStrategyState state = statePort.load(instrument, timeframe)
                .orElseGet(() -> PlaybookStrategyState.initial(instrument, timeframe, properties.getInitialEquity()));
        PlaybookStrategyState updated = state.withConfiguredOrderQty(qty);
        statePort.save(updated);
        log.info("Playbook [{} {}] order qty updated to {}", instrument, timeframe, qty);
        publishState(updated);
        return updated;
    }

    public Optional<PlaybookStrategyState> getState(String instrument, String timeframe) {
        return statePort.load(instrument, timeframe);
    }

    public List<PlaybookSignal> getRecentSignals(String instrument, String timeframe, int limit) {
        return historyPort.findRecent(instrument, timeframe, limit);
    }

    public BigDecimal getInitialEquity() {
        return properties.getInitialEquity();
    }

    public BigDecimal getMaxDailyLossUsd() {
        return properties.getMaxDailyLossUsd();
    }

    // ── WebSocket and Event publishing helpers ─────────────────────────────

    private void publishState(PlaybookStrategyState state) {
        try {
            ws.convertAndSend("/topic/playbook-state/" + state.instrument() + "/" + state.timeframe(),
                    toStatePayload(state));
        } catch (Exception e) {
            log.debug("Playbook WebSocket state publish failed: {}", e.getMessage());
        }
    }

    private void publishSimulationEvent(TradeSimulation sim) {
        try {
            ws.convertAndSend("/topic/simulations", sim);
        } catch (Exception e) {
            log.debug("Playbook Simulation WebSocket publish failed: {}", e.getMessage());
        }
    }

    private Map<String, Object> toStatePayload(PlaybookStrategyState state) {
        PlaybookProfile profile = state.activeProfile() != null ? state.activeProfile() : PlaybookProfile.BASELINE;
        Map<String, Object> payload = new HashMap<>();
        payload.put("instrument", state.instrument());
        payload.put("timeframe", state.timeframe());
        payload.put("currentDirection", state.currentPosition().name());
        payload.put("dailyPnl", state.dailyPnl());
        payload.put("dayStartEquity", state.dayStartEquity());
        payload.put("maxDailyLossUsd", properties.getMaxDailyLossUsd());
        payload.put("maxLossHit", state.maxLossHit());
        payload.put("activeProfile", profile.name());
        payload.put("autoExecutionEnabled", state.autoExecutionEnabled());
        payload.put("configuredOrderQty", state.configuredOrderQty());
        payload.put("canTrade", !state.maxLossHit() || !profile.blocksOnMaxLoss());
        return payload;
    }

    private Map<String, Object> toWsPayload(PlaybookSignal signal, PlaybookStrategyState state) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", signal.id().toString());
        payload.put("instrument", signal.instrument());
        payload.put("timeframe", signal.timeframe());
        payload.put("direction", signal.direction());
        payload.put("checklistScore", signal.checklistScore());
        payload.put("setupType", signal.setupType());
        payload.put("entryPrice", signal.entryPrice());
        payload.put("stopLoss", signal.stopLoss());
        payload.put("takeProfit1", signal.takeProfit1());
        payload.put("takeProfit2", signal.takeProfit2());
        payload.put("evaluatedAt", signal.evaluatedAt().toString());
        payload.put("routingOutcome", signal.routingOutcome() != null ? signal.routingOutcome().name() : null);
        payload.put("routingErrorMessage", signal.routingErrorMessage());
        payload.put("state", toStatePayload(state));
        return payload;
    }
}
