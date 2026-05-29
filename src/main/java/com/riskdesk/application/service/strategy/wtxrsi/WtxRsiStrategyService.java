package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingResult;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiBarEvaluator;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiConfig;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiPosition;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiRiskCalculator;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiRiskPlan;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignal;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignalRecord;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiStrategyState;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSwingBias;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSwingBiasFilter;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiTpMode;
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
import java.math.RoundingMode;
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

    public WtxRsiStrategyService(
            WtxRsiStrategyStatePort statePort,
            WtxRsiSignalHistoryPort historyPort,
            CandleRepositoryPort candlePort,
            WtxRsiStrategyProperties properties,
            ObjectProvider<WtxRsiExecutionBridge> bridgeProvider,
            SimpMessagingTemplate ws,
            WtxRsiBiasResolver biasResolver) {
        this.statePort = statePort;
        this.historyPort = historyPort;
        this.candlePort = candlePort;
        this.properties = properties;
        this.bridgeProvider = bridgeProvider;
        this.ws = ws;
        this.biasResolver = biasResolver;
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

        WtxRsiStrategyState state = statePort.load(instrumentName, event.timeframe())
                .orElseGet(() -> WtxRsiStrategyState.initial(instrumentName, event.timeframe()));

        int lastBar = candles.size() - 1;
        Candle lastCandle = candles.get(lastBar);

        // ── 1) If a position is open, check SL / TP exits on the closed candle ───
        if (state.currentPosition() != WtxRsiPosition.FLAT) {
            state = checkProtectiveExit(state, lastCandle, instrument, config);
        }

        // ── 2) Resolve the current swing bias (always — used for UI + optional filter).
        //       Source (fractal HH/HL vs SMC engine) is picked by config.biasSource(). ──
        WtxRsiSwingBias bias = biasResolver.resolve(instrument, event.timeframe(), candles, config);
        state = state.withLastSwingBias(bias);

        // ── 3) If the filter is on and the open position is on the wrong side,
        //       force-close before evaluating new signals. ─────────────────────────
        if (state.swingBiasFilterEnabled() && state.currentPosition() != WtxRsiPosition.FLAT) {
            WtxRsiSwingBiasFilter.Decision openSideDecision =
                    WtxRsiSwingBiasFilter.evaluate(null, state.currentPosition(), bias);
            if (openSideDecision == WtxRsiSwingBiasFilter.Decision.FORCE_CLOSE_LONG
                    || openSideDecision == WtxRsiSwingBiasFilter.Decision.FORCE_CLOSE_SHORT) {
                state = forceCloseFromBias(state, lastCandle, bias, config);
            }
        }

        // ── 4) Evaluate signal on this bar ───────────────────────────────────────
        Optional<WtxRsiSignal> maybeSignal =
                WtxRsiBarEvaluator.evaluate(candles, series, lastBar, config);
        if (maybeSignal.isPresent()) {
            WtxRsiSignal sig = maybeSignal.get();
            // Bias filter on fresh signals — suppress when toggled on and contradictory.
            if (state.swingBiasFilterEnabled()) {
                WtxRsiSwingBiasFilter.Decision decision =
                        WtxRsiSwingBiasFilter.evaluate(sig.side(), state.currentPosition(), bias);
                if (decision == WtxRsiSwingBiasFilter.Decision.SUPPRESS) {
                    WtxRsiSignalRecord suppressed = new WtxRsiSignalRecord(
                            state.instrument(), state.timeframe(),
                            sig.timestamp(), sig.side(),
                            WtxRsiSignalRecord.Action.NONE,
                            sig.wt1(), sig.wt2(), sig.rsi(), sig.rsiSma(),
                            sig.chaikin(), sig.confirmed(),
                            null, null, null, 0, null,
                            "swing-bias filter: " + bias.name()
                    );
                    historyPort.save(suppressed);
                    publishSignal(suppressed);
                    log.info("WTX-RSI [{} {}] signal suppressed by swing-bias filter — side={} bias={}",
                            state.instrument(), state.timeframe(), sig.side(), bias);
                } else {
                    state = handleSignal(sig, candles, state, config, instrument);
                }
            } else {
                state = handleSignal(sig, candles, state, config, instrument);
            }
        }

        // ── 5) Persist state + WS publish ────────────────────────────────────────
        state = state.withLastCandleTs(event.timestamp());
        statePort.save(state);
        publishState(state);
    }

    /**
     * Closes an open position when the swing-bias filter flips against it and
     * no opposite signal has fired yet. The exit price is the bar's close — the
     * filter is evaluated on the closed bar, so this matches the moment the
     * operator's panel would have flipped.
     */
    private WtxRsiStrategyState forceCloseFromBias(
            WtxRsiStrategyState state, Candle bar, WtxRsiSwingBias bias, WtxRsiConfig config) {
        BigDecimal exitPrice = bar.getClose();
        BigDecimal realized = realizedPnl(state, exitPrice, config);
        WtxRsiSignalRecord.Action action = state.currentPosition() == WtxRsiPosition.LONG
                ? WtxRsiSignalRecord.Action.CLOSE_LONG
                : WtxRsiSignalRecord.Action.CLOSE_SHORT;
        WtxRoutingResult routing = routeClose(state, action, exitPrice);
        WtxRsiSignalRecord rec = new WtxRsiSignalRecord(
                state.instrument(), state.timeframe(),
                bar.getTimestamp(),
                state.currentPosition() == WtxRsiPosition.LONG
                        ? WtxRsiSignal.Side.LONG : WtxRsiSignal.Side.SHORT,
                action,
                null, null, null, null, null, false,
                exitPrice, state.stopLoss(), state.takeProfit(),
                state.entryQty().intValue(),
                routing.outcome(),
                "swing-bias flip → " + bias.name()
        );
        historyPort.save(rec);
        publishSignal(rec);
        log.info("WTX-RSI [{} {}] force-close by swing-bias filter — newBias={} exit={} routing={}",
                state.instrument(), state.timeframe(), bias, exitPrice, routing.outcome());
        return state.withFlat(realized);
    }

    /**
     * Closes the open position when the bar's intra-bar high/low touches SL or TP.
     * Pessimistic rule: if both touched in the same bar, SL wins. Matches
     * {@code TradeSimulationService} and the backtest engine.
     */
    private WtxRsiStrategyState checkProtectiveExit(
            WtxRsiStrategyState state, Candle bar, Instrument instrument, WtxRsiConfig config) {
        boolean isLong = state.currentPosition() == WtxRsiPosition.LONG;
        BigDecimal sl = state.stopLoss();
        BigDecimal tp = state.takeProfit();
        BigDecimal high = bar.getHigh();
        BigDecimal low = bar.getLow();

        boolean slHit = sl != null
                && (isLong ? low.compareTo(sl) <= 0 : high.compareTo(sl) >= 0);
        boolean tpHit = tp != null
                && (isLong ? high.compareTo(tp) >= 0 : low.compareTo(tp) <= 0);
        if (!slHit && !tpHit) return state;

        BigDecimal exitPrice = slHit ? sl : tp;
        BigDecimal realized = realizedPnl(state, exitPrice, config);
        WtxRsiSignalRecord.Action action = isLong
                ? WtxRsiSignalRecord.Action.CLOSE_LONG
                : WtxRsiSignalRecord.Action.CLOSE_SHORT;
        WtxRoutingResult routing = routeClose(state, action, exitPrice);

        WtxRsiSignalRecord record = new WtxRsiSignalRecord(
                state.instrument(), state.timeframe(),
                bar.getTimestamp(),
                isLong ? WtxRsiSignal.Side.LONG : WtxRsiSignal.Side.SHORT,
                action,
                null, null, null, null, null, false,
                exitPrice, state.stopLoss(), state.takeProfit(),
                state.entryQty().intValue(),
                routing.outcome(), routing.errorMessage()
        );
        historyPort.save(record);
        publishSignal(record);

        log.info("WTX-RSI [{} {}] protective exit — {} at {} (slHit={}, tpHit={}) routing={}",
                state.instrument(), state.timeframe(),
                slHit ? "SL_HIT" : "TP_HIT", exitPrice, slHit, tpHit, routing.outcome());
        return state.withFlat(realized);
    }

    /**
     * Handles a newly-emitted signal: opens if flat, reverses if opposite to current.
     */
    private WtxRsiStrategyState handleSignal(
            WtxRsiSignal signal, List<Candle> candles,
            WtxRsiStrategyState state, WtxRsiConfig config, Instrument instrument) {

        // Reversal-on-opposite-signal (TP mode REVERSAL) — close current side first.
        if (state.currentPosition() != WtxRsiPosition.FLAT) {
            boolean opposite = (signal.side() == WtxRsiSignal.Side.SHORT && state.currentPosition() == WtxRsiPosition.LONG)
                    || (signal.side() == WtxRsiSignal.Side.LONG && state.currentPosition() == WtxRsiPosition.SHORT);
            if (opposite && config.tpMode() == WtxRsiTpMode.REVERSAL) {
                BigDecimal exitPrice = signal.close();
                BigDecimal realized = realizedPnl(state, exitPrice, config);
                WtxRsiSignalRecord.Action closeAction = state.currentPosition() == WtxRsiPosition.LONG
                        ? WtxRsiSignalRecord.Action.CLOSE_LONG
                        : WtxRsiSignalRecord.Action.CLOSE_SHORT;
                WtxRoutingResult closeRouting = routeClose(state, closeAction, exitPrice);
                WtxRsiSignalRecord closeRecord = new WtxRsiSignalRecord(
                        state.instrument(), state.timeframe(),
                        signal.timestamp(),
                        state.currentPosition() == WtxRsiPosition.LONG
                                ? WtxRsiSignal.Side.LONG : WtxRsiSignal.Side.SHORT,
                        closeAction,
                        signal.wt1(), signal.wt2(), signal.rsi(), signal.rsiSma(),
                        signal.chaikin(), signal.confirmed(),
                        exitPrice, state.stopLoss(), state.takeProfit(),
                        state.entryQty().intValue(),
                        closeRouting.outcome(), closeRouting.errorMessage()
                );
                historyPort.save(closeRecord);
                publishSignal(closeRecord);
                state = state.withFlat(realized);
            } else {
                // Same-side signal while open — suppress
                WtxRsiSignalRecord suppressed = new WtxRsiSignalRecord(
                        state.instrument(), state.timeframe(),
                        signal.timestamp(), signal.side(),
                        WtxRsiSignalRecord.Action.NONE,
                        signal.wt1(), signal.wt2(), signal.rsi(), signal.rsiSma(),
                        signal.chaikin(), signal.confirmed(),
                        null, null, null, 0, null, null
                );
                historyPort.save(suppressed);
                publishSignal(suppressed);
                return state;
            }
        }

        // Entry-only Chaikin gate. When chaikin-required is on, an unconfirmed
        // signal may NOT open a position. Any exit triggered above (reversal
        // close, or SL/TP earlier in the candle) has already executed — only the
        // fresh entry is blocked here, so the exit mechanism is unchanged.
        // No-op when Chaikin confirmation isn't computed (chaikinEnabled=false).
        if (config.chaikinRequired() && config.chaikinEnabled() && !signal.confirmed()) {
            WtxRsiSignalRecord blocked = new WtxRsiSignalRecord(
                    state.instrument(), state.timeframe(),
                    signal.timestamp(), signal.side(),
                    WtxRsiSignalRecord.Action.NONE,
                    signal.wt1(), signal.wt2(), signal.rsi(), signal.rsiSma(),
                    signal.chaikin(), signal.confirmed(),
                    null, null, null, 0, null,
                    "chaikin-required: entry blocked (Chaikin not confirmed)"
            );
            historyPort.save(blocked);
            publishSignal(blocked);
            log.info("WTX-RSI [{} {}] entry blocked by chaikin-required — side={} (unconfirmed)",
                    state.instrument(), state.timeframe(), signal.side());
            return state;
        }

        // Open new position
        BigDecimal entryPrice = signal.close();
        Optional<WtxRsiRiskPlan> maybePlan = WtxRsiRiskCalculator.build(signal, candles, entryPrice, config);
        if (maybePlan.isEmpty()) {
            log.info("WTX-RSI [{} {}] signal rejected — no confirmed fractal in range",
                    state.instrument(), state.timeframe());
            return state;
        }
        WtxRsiRiskPlan plan = maybePlan.get();

        // Honour the user's configured order qty (override the auto base/confirmed sizing
        // when the user has set a panel quantity). configuredOrderQty=DEFAULT_ORDER_QTY (1)
        // by default — same intent as the WTX panel quantity input.
        int contracts = state.configuredOrderQty() > 0
                ? state.configuredOrderQty() * (signal.confirmed() ? config.confirmedMultiplier() : 1)
                : plan.contracts();
        WtxRsiRiskPlan finalPlan = new WtxRsiRiskPlan(
                plan.side(), contracts, plan.entryPrice(), plan.stopLoss(),
                plan.takeProfit(), plan.initialRiskPerContract(), plan.swingReference());

        WtxRoutingResult routing = routeOpen(signal, finalPlan, state, entryPrice);

        WtxRsiPosition newPos = signal.side() == WtxRsiSignal.Side.LONG
                ? WtxRsiPosition.LONG : WtxRsiPosition.SHORT;
        state = state.withPosition(newPos, finalPlan.entryPrice(),
                BigDecimal.valueOf(finalPlan.contracts()), finalPlan.stopLoss(), finalPlan.takeProfit());

        WtxRsiSignalRecord record = new WtxRsiSignalRecord(
                state.instrument(), state.timeframe(),
                signal.timestamp(), signal.side(),
                signal.side() == WtxRsiSignal.Side.LONG
                        ? WtxRsiSignalRecord.Action.OPEN_LONG
                        : WtxRsiSignalRecord.Action.OPEN_SHORT,
                signal.wt1(), signal.wt2(), signal.rsi(), signal.rsiSma(),
                signal.chaikin(), signal.confirmed(),
                finalPlan.entryPrice(), finalPlan.stopLoss(), finalPlan.takeProfit(),
                finalPlan.contracts(),
                routing.outcome(), routing.errorMessage()
        );
        historyPort.save(record);
        publishSignal(record);

        log.info("WTX-RSI [{} {}] OPEN {} qty={} entry={} SL={} TP={} routing={}",
                state.instrument(), state.timeframe(),
                signal.side(), finalPlan.contracts(),
                finalPlan.entryPrice(), finalPlan.stopLoss(), finalPlan.takeProfit(),
                routing.outcome());

        return state;
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

    private BigDecimal realizedPnl(WtxRsiStrategyState state, BigDecimal exitPrice, WtxRsiConfig config) {
        if (state.entryPrice() == null || state.entryQty() == null) return BigDecimal.ZERO;
        BigDecimal direction = state.currentPosition() == WtxRsiPosition.LONG
                ? BigDecimal.ONE : BigDecimal.ONE.negate();
        BigDecimal points = exitPrice.subtract(state.entryPrice()).multiply(direction);
        BigDecimal ticks = points.divide(config.tickSize(), 4, RoundingMode.HALF_UP);
        return ticks.multiply(config.tickValueUsd()).multiply(state.entryQty());
    }

    // ── Public API used by the REST controller ─────────────────────────────

    public Optional<WtxRsiStrategyState> getState(String instrument, String timeframe) {
        return statePort.load(instrument, timeframe);
    }

    public WtxRsiStrategyState toggleAutoExecution(String instrument, String timeframe, boolean enabled) {
        WtxRsiStrategyState state = statePort.load(instrument, timeframe)
                .orElseGet(() -> WtxRsiStrategyState.initial(instrument, timeframe));
        WtxRsiStrategyState updated = state.withAutoExecution(enabled);
        statePort.save(updated);
        log.warn("WTX-RSI [{} {}] auto-execution {} — IBKR routing is now {}",
                instrument, timeframe, enabled ? "ENABLED" : "DISABLED",
                enabled ? "LIVE" : "off");
        publishState(updated);
        return updated;
    }

    public WtxRsiStrategyState setOrderQty(String instrument, String timeframe, int qty) {
        WtxRsiStrategyState state = statePort.load(instrument, timeframe)
                .orElseGet(() -> WtxRsiStrategyState.initial(instrument, timeframe));
        WtxRsiStrategyState updated = state.withConfiguredOrderQty(qty);
        statePort.save(updated);
        publishState(updated);
        return updated;
    }

    public WtxRsiStrategyState toggleSwingBiasFilter(String instrument, String timeframe, boolean enabled) {
        WtxRsiStrategyState state = statePort.load(instrument, timeframe)
                .orElseGet(() -> WtxRsiStrategyState.initial(instrument, timeframe));
        WtxRsiStrategyState updated = state.withSwingBiasFilter(enabled);
        statePort.save(updated);
        log.info("WTX-RSI [{} {}] swing-bias filter {}",
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
        p.put("lastSwingBias", s.lastSwingBias() != null ? s.lastSwingBias().name() : null);
        p.put("biasSource", properties.getBiasSource() != null ? properties.getBiasSource().name() : null);
        return p;
    }
}
