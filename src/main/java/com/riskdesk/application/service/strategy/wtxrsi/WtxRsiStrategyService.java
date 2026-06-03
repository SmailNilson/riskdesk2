package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingResult;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiBarEvaluator;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiConfig;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiDecision;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiPosition;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiRiskPlan;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignal;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignalRecord;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiStrategyState;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSwingBias;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiTransition;
import com.riskdesk.domain.engine.strategy.wtxrsi.port.WtxRsiSignalHistoryPort;
import com.riskdesk.domain.engine.strategy.wtxrsi.port.WtxRsiStrategyStatePort;
import com.riskdesk.domain.marketdata.event.CandleClosed;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.config.WtxRsiStrategyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Live orchestrator for the WTX+RSI strategy. Mirrors {@code WtxStrategyService}:
 * <ul>
 *   <li>Activated by {@code riskdesk.wtxrsi.enabled=true}.</li>
 *   <li>Listens to {@link CandleClosed} and runs only for (instrument, timeframe)
 *       combinations listed in {@code WtxRsiStrategyProperties.instruments/timeframes}.</li>
 *   <li>On each candle: refresh open position (SL / TP / reversal-on-opposite-signal),
 *       then evaluate a fresh entry signal.</li>
 *   <li>Routes to IBKR <b>only when</b> {@code state.autoExecutionEnabled()} is true.
 *       Otherwise emits a {@code SKIPPED_AUTO_OFF} routing outcome (pure simulation).</li>
 *   <li>Persists every fired signal (with action + routing outcome) via the history port.
 *       Publishes WS payloads on {@code /topic/wtxrsi-signals} and {@code /topic/wtxrsi-state/{instrument}/{tf}}.</li>
 * </ul>
 *
 * <p><b>Why a separate service from WtxStrategyService?</b> The signal logic, risk model
 * (Williams fractal vs ATR trailing), and config shape diverge enough that bolting RSI
 * confirmation into the WTX state machine would entangle two strategies. Keeping them
 * parallel preserves the option to A/B them on the same instrument and timeframe.</p>
 */
@Service
@ConditionalOnProperty(name = "riskdesk.wtxrsi.enabled", havingValue = "true")
public class WtxRsiStrategyService {

    private static final Logger log = LoggerFactory.getLogger(WtxRsiStrategyService.class);
    private static final int WARMUP_BARS = 200;

    private final WtxRsiStrategyStatePort statePort;
    private final WtxRsiSignalHistoryPort historyPort;
    private final CandleRepositoryPort candlePort;
    private final WtxRsiStrategyProperties properties;
    private final ObjectProvider<WtxRsiExecutionBridge> bridgeProvider;
    private final SimpMessagingTemplate ws;
    private final WtxRsiBiasResolver biasResolver;
    /** P2.2 (R3) — re-syncs the virtual side to execution-row truth each bar. Nullable in tests. */
    private final WtxRsiPositionReconciler positionReconciler;

    /**
     * Per-(instrument, timeframe) monitors serialising the load → reduce → save
     * read-modify-write. A candle close and a concurrent REST toggle would
     * otherwise race on the same row and lose an update; this also prevents two
     * overlapping cycles from routing duplicate IBKR orders for the same key.
     * Single-node guard (this app is not clustered).
     */
    private final java.util.concurrent.ConcurrentMap<String, Object> stateLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Object lockFor(String instrument, String timeframe) {
        return stateLocks.computeIfAbsent(instrument + ":" + timeframe, k -> new Object());
    }

    public WtxRsiStrategyService(
            WtxRsiStrategyStatePort statePort,
            WtxRsiSignalHistoryPort historyPort,
            CandleRepositoryPort candlePort,
            WtxRsiStrategyProperties properties,
            ObjectProvider<WtxRsiExecutionBridge> bridgeProvider,
            SimpMessagingTemplate ws,
            WtxRsiBiasResolver biasResolver,
            WtxRsiPositionReconciler positionReconciler) {
        this.statePort = statePort;
        this.historyPort = historyPort;
        this.candlePort = candlePort;
        this.properties = properties;
        this.bridgeProvider = bridgeProvider;
        this.ws = ws;
        this.biasResolver = biasResolver;
        this.positionReconciler = positionReconciler;
    }

    @EventListener
    public void onCandleClosed(CandleClosed event) {
        if (!properties.getInstruments().contains(event.instrument())) return;
        if (!properties.getTimeframes().contains(event.timeframe())) return;

        String instrumentName = event.instrument();
        Instrument instrument;
        try {
            instrument = Instrument.valueOf(instrumentName);
        } catch (IllegalArgumentException e) {
            return;
        }

        List<Candle> candles = new ArrayList<>(
                candlePort.findRecentCandles(instrument, event.timeframe(), WARMUP_BARS));
        Collections.reverse(candles); // repo returns newest-first
        if (candles.size() < 30) return;

        WtxRsiConfig config = properties.toConfig();
        WtxRsiBarEvaluator.IndicatorSeries series =
                WtxRsiBarEvaluator.computeIndicators(candles, config);

        int lastBar = candles.size() - 1;
        Candle lastCandle = candles.get(lastBar);

        // Resolve the swing bias upstream (it may consult the application-layer SMC
        // engine, so it cannot live inside the pure reducer) and evaluate the signal.
        // Both are read-only w.r.t. strategy state, so they run outside the lock.
        WtxRsiSwingBias bias = biasResolver.resolve(instrument, event.timeframe(), candles, config);
        Optional<WtxRsiSignal> signal =
                WtxRsiBarEvaluator.evaluate(candles, series, lastBar, config);

        // Serialise the load → reduce → execute → save cycle per (instrument, timeframe)
        // so a concurrent toggle / overlapping candle can't lose an update or route a
        // duplicate order. The pure FSM (reduce) is the single source of truth shared
        // with the backtest engine; execute() is the ONLY place that performs I/O.
        WtxRsiStrategyState saved;
        synchronized (lockFor(instrumentName, event.timeframe())) {
            WtxRsiStrategyState state = loadOrInit(instrumentName, event.timeframe());
            // P2.2 (R3) — self-heal the virtual side against execution-row truth (which P1 keeps aligned with
            // the broker) BEFORE the FSM reduces, so a phantom side can't emit NONE / ENTRY-IN-FLIGHT while
            // IBKR is flat. No-op for paper (auto off) or when already aligned.
            if (positionReconciler != null) {
                state = positionReconciler.reconcile(state, instrument, lastCandle.getClose());
            }
            WtxRsiTransition.Result result =
                    WtxRsiTransition.reduce(state, lastCandle, candles, signal, bias, config);
            for (WtxRsiDecision decision : result.decisions()) {
                execute(decision, instrumentName, event.timeframe());
            }
            saved = result.newState().withLastCandleTs(event.timestamp());
            statePort.save(saved);
        }
        publishState(saved);
    }

    // ── Decision interpreter — the ONLY place that performs I/O ────────────────

    /**
     * Interprets a single {@link WtxRsiDecision} emitted by {@link WtxRsiTransition}:
     * routes to IBKR, persists the append-only history row, and publishes the WS
     * payload. The {@code switch} is exhaustive over the sealed decision type, so
     * a new decision kind forces a compile error here until it is handled.
     */
    private void execute(WtxRsiDecision decision, String instrument, String timeframe) {
        switch (decision) {
            case WtxRsiDecision.Open o -> executeOpen(o, instrument, timeframe);
            case WtxRsiDecision.Close c -> executeClose(c, instrument, timeframe);
            case WtxRsiDecision.Suppress s -> executeSuppress(s, instrument, timeframe);
            case WtxRsiDecision.Block b -> executeBlock(b, instrument, timeframe);
            case WtxRsiDecision.Reject r -> log.info(
                    "WTX-RSI [{} {}] signal rejected — {}", instrument, timeframe, r.reason());
        }
    }

    private void executeOpen(WtxRsiDecision.Open o, String instrument, String timeframe) {
        WtxRsiSignal sig = o.signal();
        WtxRsiRiskPlan plan = o.plan();
        WtxRoutingResult routing = routeOpen(sig, plan, o.stateForRouting(), sig.close());
        WtxRsiSignalRecord record = new WtxRsiSignalRecord(
                instrument, timeframe, sig.timestamp(), sig.side(),
                sig.side() == WtxRsiSignal.Side.LONG
                        ? WtxRsiSignalRecord.Action.OPEN_LONG
                        : WtxRsiSignalRecord.Action.OPEN_SHORT,
                sig.wt1(), sig.wt2(), sig.rsi(), sig.rsiSma(), sig.chaikin(), sig.confirmed(),
                plan.entryPrice(), plan.stopLoss(), plan.takeProfit(), plan.contracts(),
                routing.outcome(), routing.errorMessage());
        historyPort.save(record);
        publishSignal(record);
        log.info("WTX-RSI [{} {}] OPEN {} qty={} entry={} SL={} TP={} routing={}",
                instrument, timeframe, sig.side(), plan.contracts(),
                plan.entryPrice(), plan.stopLoss(), plan.takeProfit(), routing.outcome());
    }

    private void executeClose(WtxRsiDecision.Close c, String instrument, String timeframe) {
        // Route with the pre-close snapshot — it still carries the open qty/SL/TP
        // the broker bridge needs (the running state is already FLAT by now).
        WtxRsiStrategyState st = c.stateBeforeClose();
        boolean isLong = st.currentPosition() == WtxRsiPosition.LONG;
        WtxRsiSignalRecord.Action action = isLong
                ? WtxRsiSignalRecord.Action.CLOSE_LONG
                : WtxRsiSignalRecord.Action.CLOSE_SHORT;
        WtxRoutingResult routing = routeClose(st, action, c.exitPrice());

        WtxRsiSignal sig = c.signal(); // non-null only for REVERSAL
        boolean hasSig = sig != null;
        String message = c.reasonOverride() != null ? c.reasonOverride() : routing.errorMessage();
        WtxRsiSignalRecord record = new WtxRsiSignalRecord(
                instrument, timeframe, c.timestamp(),
                isLong ? WtxRsiSignal.Side.LONG : WtxRsiSignal.Side.SHORT,
                action,
                hasSig ? sig.wt1() : null, hasSig ? sig.wt2() : null,
                hasSig ? sig.rsi() : null, hasSig ? sig.rsiSma() : null,
                hasSig ? sig.chaikin() : null, hasSig && sig.confirmed(),
                c.exitPrice(), st.stopLoss(), st.takeProfit(),
                st.entryQty() != null ? st.entryQty().intValue() : 0,
                routing.outcome(), message);
        historyPort.save(record);
        publishSignal(record);
        log.info("WTX-RSI [{} {}] CLOSE {} ({}) at {} routing={}",
                instrument, timeframe, action, c.cause(), c.exitPrice(), routing.outcome());
    }

    private void executeSuppress(WtxRsiDecision.Suppress s, String instrument, String timeframe) {
        WtxRsiSignal sig = s.signal();
        WtxRsiSignalRecord record = new WtxRsiSignalRecord(
                instrument, timeframe, sig.timestamp(), sig.side(),
                WtxRsiSignalRecord.Action.NONE,
                sig.wt1(), sig.wt2(), sig.rsi(), sig.rsiSma(), sig.chaikin(), sig.confirmed(),
                null, null, null, 0, null, s.reason());
        historyPort.save(record);
        publishSignal(record);
    }

    private void executeBlock(WtxRsiDecision.Block b, String instrument, String timeframe) {
        WtxRsiSignal sig = b.signal();
        WtxRsiSignalRecord record = new WtxRsiSignalRecord(
                instrument, timeframe, sig.timestamp(), sig.side(),
                WtxRsiSignalRecord.Action.NONE,
                sig.wt1(), sig.wt2(), sig.rsi(), sig.rsiSma(), sig.chaikin(), sig.confirmed(),
                null, null, null, 0, null, b.reason());
        historyPort.save(record);
        publishSignal(record);
        log.info("WTX-RSI [{} {}] entry blocked by chaikin-required — side={} (unconfirmed)",
                instrument, timeframe, sig.side());
    }

    private WtxRoutingResult routeOpen(
            WtxRsiSignal signal, WtxRsiRiskPlan plan,
            WtxRsiStrategyState state, BigDecimal refPrice) {
        if (!state.autoExecutionEnabled()) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_AUTO_OFF);
        }
        WtxRsiExecutionBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge == null) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_BRIDGE_UNAVAILABLE);
        }
        try {
            return bridge.submitOpen(signal, plan, state, refPrice);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "execution bridge failure" : e.getMessage();
            return WtxRoutingResult.of(WtxRoutingOutcome.FAILED,
                    msg.length() > 200 ? msg.substring(0, 200) : msg);
        }
    }

    private WtxRoutingResult routeClose(
            WtxRsiStrategyState state, WtxRsiSignalRecord.Action action, BigDecimal refPrice) {
        if (!state.autoExecutionEnabled()) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_AUTO_OFF);
        }
        WtxRsiExecutionBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge == null) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_BRIDGE_UNAVAILABLE);
        }
        try {
            return bridge.submitClose(state, action, refPrice);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "execution bridge failure" : e.getMessage();
            return WtxRoutingResult.of(WtxRoutingOutcome.FAILED,
                    msg.length() > 200 ? msg.substring(0, 200) : msg);
        }
    }

    // ── Public API used by the REST controller ─────────────────────────────

    public Optional<WtxRsiStrategyState> getState(String instrument, String timeframe) {
        return statePort.load(instrument, timeframe);
    }

    /**
     * Current state, or a fresh one seeded with the global Chaikin-gate default
     * when none persisted yet — so the panel reflects the configured default
     * before any candle has created a row.
     */
    public WtxRsiStrategyState getStateOrInitial(String instrument, String timeframe) {
        return loadOrInit(instrument, timeframe);
    }

    /** Load persisted state, or seed a fresh one inheriting the global Chaikin-gate default. */
    private WtxRsiStrategyState loadOrInit(String instrument, String timeframe) {
        return statePort.load(instrument, timeframe)
                .orElseGet(() -> WtxRsiStrategyState.initial(
                        instrument, timeframe, properties.isChaikinRequired()));
    }

    public WtxRsiStrategyState toggleAutoExecution(String instrument, String timeframe, boolean enabled) {
        WtxRsiStrategyState updated;
        synchronized (lockFor(instrument, timeframe)) {
            updated = loadOrInit(instrument, timeframe).withAutoExecution(enabled);
            statePort.save(updated);
        }
        log.warn("WTX-RSI [{} {}] auto-execution {} — IBKR routing is now {}",
                instrument, timeframe, enabled ? "ENABLED" : "DISABLED",
                enabled ? "LIVE" : "off");
        publishState(updated);
        return updated;
    }

    public WtxRsiStrategyState setOrderQty(String instrument, String timeframe, int qty) {
        WtxRsiStrategyState updated;
        synchronized (lockFor(instrument, timeframe)) {
            updated = loadOrInit(instrument, timeframe).withConfiguredOrderQty(qty);
            statePort.save(updated);
        }
        publishState(updated);
        return updated;
    }

    public WtxRsiStrategyState toggleSwingBiasFilter(String instrument, String timeframe, boolean enabled) {
        WtxRsiStrategyState updated;
        synchronized (lockFor(instrument, timeframe)) {
            updated = loadOrInit(instrument, timeframe).withSwingBiasFilter(enabled);
            statePort.save(updated);
        }
        log.info("WTX-RSI [{} {}] swing-bias filter {}",
                instrument, timeframe, enabled ? "ENABLED" : "DISABLED");
        publishState(updated);
        return updated;
    }

    public WtxRsiStrategyState toggleChaikinRequired(String instrument, String timeframe, boolean enabled) {
        WtxRsiStrategyState updated;
        synchronized (lockFor(instrument, timeframe)) {
            updated = loadOrInit(instrument, timeframe).withChaikinRequired(enabled);
            statePort.save(updated);
        }
        log.info("WTX-RSI [{} {}] chaikin-required entry gate {}",
                instrument, timeframe, enabled ? "ENABLED" : "DISABLED");
        publishState(updated);
        return updated;
    }

    public List<WtxRsiSignalRecord> recentSignals(String instrument, String timeframe, int limit) {
        if (timeframe == null || timeframe.isBlank()) {
            return historyPort.findRecent(instrument, limit);
        }
        return historyPort.findRecent(instrument, timeframe, limit);
    }

    // ── WebSocket publishing ───────────────────────────────────────────────

    private void publishSignal(WtxRsiSignalRecord record) {
        try {
            ws.convertAndSend("/topic/wtxrsi-signals", toSignalPayload(record));
        } catch (Exception e) {
            log.debug("WTX-RSI WS signal publish failed", e);
        }
    }

    private void publishState(WtxRsiStrategyState state) {
        // Single topic — the payload carries instrument + timeframe so the client
        // keys by (instrument, timeframe) itself. Same fanout shape as the signals
        // topic and avoids the wildcard-subscription portability concerns that a
        // per-tuple topic ({i}/{tf}) introduces with Spring's SimpleBroker.
        try {
            ws.convertAndSend("/topic/wtxrsi-state", toStatePayload(state));
        } catch (Exception e) {
            log.debug("WTX-RSI WS state publish failed", e);
        }
    }

    private Map<String, Object> toSignalPayload(WtxRsiSignalRecord r) {
        Map<String, Object> p = new HashMap<>();
        p.put("instrument", r.instrument());
        p.put("timeframe", r.timeframe());
        p.put("signalTs", r.signalTs().toString());
        p.put("side", r.side().name());
        p.put("action", r.action().name());
        p.put("wt1", r.wt1());
        p.put("wt2", r.wt2());
        p.put("rsi", r.rsi());
        p.put("rsiSma", r.rsiSma());
        p.put("chaikin", r.chaikin());
        p.put("chaikinConfirmed", r.chaikinConfirmed());
        p.put("entryPrice", r.entryPrice());
        p.put("stopLoss", r.stopLoss());
        p.put("takeProfit", r.takeProfit());
        p.put("contracts", r.contracts());
        p.put("routingOutcome", r.routingOutcome() != null ? r.routingOutcome().name() : null);
        p.put("routingErrorMessage", r.routingErrorMessage());
        return p;
    }

    private Map<String, Object> toStatePayload(WtxRsiStrategyState s) {
        Map<String, Object> p = new HashMap<>();
        p.put("instrument", s.instrument());
        p.put("timeframe", s.timeframe());
        p.put("currentPosition", s.currentPosition().name());
        p.put("entryPrice", s.entryPrice());
        p.put("entryQty", s.entryQty());
        p.put("stopLoss", s.stopLoss());
        p.put("takeProfit", s.takeProfit());
        p.put("cumulativeRealizedPnl", s.cumulativeRealizedPnl());
        p.put("autoExecutionEnabled", s.autoExecutionEnabled());
        p.put("configuredOrderQty", s.configuredOrderQty());
        p.put("swingBiasFilterEnabled", s.swingBiasFilterEnabled());
        p.put("chaikinRequired", s.chaikinRequired());
        p.put("lastSwingBias", s.lastSwingBias() != null ? s.lastSwingBias().name() : null);
        p.put("biasSource", properties.getBiasSource() != null ? properties.getBiasSource().name() : null);
        return p;
    }
}
