package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.indicators.AtrCalculator;
import com.riskdesk.domain.engine.indicators.EMAIndicator;
import com.riskdesk.domain.engine.indicators.WaveTrendIndicator;
import com.riskdesk.domain.engine.indicators.WaveTrendIndicator.WaveTrendResult;
import com.riskdesk.domain.engine.strategy.wtx.*;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxSignalHistoryPort;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxStrategyStatePort;
import com.riskdesk.domain.marketdata.event.CandleClosed;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.notification.event.WtxSignalDetectedEvent;
import com.riskdesk.infrastructure.config.WtxStrategyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrator for the WaveTrend XT strategy.
 * Completely independent from AlertService and MentorSignalReviewService.
 * Enabled via riskdesk.wtx.enabled=true.
 */
@Service
@ConditionalOnProperty(name = "riskdesk.wtx.enabled", havingValue = "true")
public class WtxStrategyService {

    private static final Logger log = LoggerFactory.getLogger(WtxStrategyService.class);
    private static final int WARMUP_BARS = 100;

    private final WtxStrategyStatePort statePort;
    private final WtxSignalHistoryPort historyPort;
    private final CandleRepositoryPort candlePort;
    private final WtxEnrichmentBuilder enrichmentBuilder;
    private final SimpMessagingTemplate ws;
    private final WtxStrategyProperties properties;
    private final ObjectProvider<WtxExecutionBridge> executionBridgeProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final WtxClosePnlSettler closePnlSettler;
    private final WtxPositionReconciler positionReconciler;

    public WtxStrategyService(
            WtxStrategyStatePort statePort,
            WtxSignalHistoryPort historyPort,
            CandleRepositoryPort candlePort,
            WtxEnrichmentBuilder enrichmentBuilder,
            SimpMessagingTemplate ws,
            WtxStrategyProperties properties,
            ObjectProvider<WtxExecutionBridge> executionBridgeProvider,
            ApplicationEventPublisher eventPublisher,
            WtxClosePnlSettler closePnlSettler,
            WtxPositionReconciler positionReconciler
    ) {
        this.statePort = statePort;
        this.historyPort = historyPort;
        this.candlePort = candlePort;
        this.enrichmentBuilder = enrichmentBuilder;
        this.ws = ws;
        this.properties = properties;
        this.executionBridgeProvider = executionBridgeProvider;
        this.eventPublisher = eventPublisher;
        this.closePnlSettler = closePnlSettler;
        this.positionReconciler = positionReconciler;
    }

    private void publishWtxEvent(WtxSignal signal, BigDecimal price) {
        try {
            eventPublisher.publishEvent(WtxSignalDetectedEvent.from(signal, price));
        } catch (Exception e) {
            log.warn("Failed to publish WtxSignalDetectedEvent for {} {} — {}",
                    signal.instrument(), signal.timeframe(), e.getMessage());
        }
    }

    @EventListener
    public void onCandleClosed(CandleClosed event) {
        WtxConfig config = properties.toConfig();
        if (!config.instruments().contains(event.instrument())) return;
        if (!config.timeframes().contains(event.timeframe())) return;

        String instrumentName = event.instrument();

        Instrument instrument;
        try {
            instrument = Instrument.valueOf(instrumentName);
        } catch (IllegalArgumentException e) {
            return;
        }

        // findRecentCandles returns DESC (newest first) — reverse to chronological for WT computation
        List<Candle> candles = new java.util.ArrayList<>(
                candlePort.findRecentCandles(instrument, event.timeframe(), WARMUP_BARS));
        java.util.Collections.reverse(candles);
        if (candles.size() < 2) return;

        WaveTrendIndicator wt = new WaveTrendIndicator(config.n1(), config.n2(), config.signalPeriod());
        List<WaveTrendResult> results = wt.calculate(candles);
        if (results.size() < 2) return;

        WaveTrendResult prev = WtxBarEvaluator.prev(results);
        WaveTrendResult curr = WtxBarEvaluator.curr(results);

        WtxStrategyState state = statePort.load(instrumentName, event.timeframe())
                .orElseGet(() -> WtxStrategyState.initial(instrumentName, event.timeframe(),
                        properties.getInitialEquity()));

        // ── 0. Settle any optimistic close P&L against execution-row truth (roll back an unfilled close,
        // finalize a confirmed one) BEFORE day-reset archives the day's realized P&L into dayStartEquity.
        state = closePnlSettler.settle(state);

        // Day-change detection (America/New_York)
        if (WtxRiskGuard.isNewTradingDay(state.lastCandleTs(), event.timestamp())) {
            BigDecimal equity = state.currentEquity();
            state = state.withDayReset(equity);
            log.info("WTX [{}] new trading day — equity reset to {}", instrumentName, equity);
        }

        WtxProfile profile = state.activeProfile() != null ? state.activeProfile() : WtxProfile.BASELINE;
        Candle currentCandle = candles.get(candles.size() - 1);

        // ── 0b. Reconcile the position SIDE against execution-row truth (runs AFTER the close-P&L settle).
        // Self-heals an async divergence (a reverse close that cancelled, a missed fill, a restart, a manual
        // close); no-op when paper / aligned / a close is still pending (the settler owns that window).
        state = positionReconciler.reconcile(state, instrument, currentCandle.getClose());

        // ── 1. Trailing exit check (profile >= SESSION_ATR) ───────────────────
        if (profile.requiresAtrExits() && state.currentPosition() != WtxPosition.FLAT) {
            WtxTrailingExitEvaluator.Decision exit =
                    WtxTrailingExitEvaluator.evaluate(state, currentCandle, config);
            if (exit.shouldExit()) {
                state = applyExit(state, instrument, exit, event, profile);
            } else {
                state = state.withTrailing(exit.updatedBestFavorablePrice(), exit.updatedTrailingStopPrice());
            }
        }

        // ── 2. Filter contexts (HTF + Structure) ──────────────────────────────
        WtxHtfBiasFilter.HtfBiasContext htfCtx = null;
        WtxHtfBiasFilter.Decision htfDecision = null;
        if (profile.requiresHtfFilter()) {
            htfCtx = buildHtfContext(instrument, config);
            htfDecision = WtxHtfBiasFilter.evaluate("LONG", htfCtx); // placeholder — recomputed per direction below
        }

        WtxStructureFilter.Decision structureDecision = null;
        if (profile.requiresStructureFilter()) {
            BigDecimal atr = AtrCalculator.compute(candles, config.atrLength());
            structureDecision = WtxStructureFilter.evaluate(
                    "LONG", candles, atr, config.structureLookback(), config.sweepBufferAtr()); // also placeholder
        }

        // Re-evaluate per direction after detecting it
        Optional<WtxSignal> prelim = WtxBarEvaluator.evaluate(
                prev, curr, config, state, event.timestamp(), event.timeframe(), null, null);

        if (prelim.isPresent()) {
            WtxSignal probe = prelim.get();
            String dir = probe.direction();
            if (profile.requiresHtfFilter()) {
                htfDecision = WtxHtfBiasFilter.evaluate(dir, htfCtx);
            }
            if (profile.requiresStructureFilter()) {
                BigDecimal atr = AtrCalculator.compute(candles, config.atrLength());
                structureDecision = WtxStructureFilter.evaluate(
                        dir, candles, atr, config.structureLookback(), config.sweepBufferAtr());
            }
        }

        // ── 3. Final signal evaluation with filter decisions ──────────────────
        Optional<WtxSignal> maybeSignal = WtxBarEvaluator.evaluate(
                prev, curr, config, state, event.timestamp(), event.timeframe(),
                htfDecision, structureDecision);

        if (maybeSignal.isPresent()) {
            WtxSignal signal = maybeSignal.get();

            WtxEnrichmentSnapshot enrichment = enrichmentBuilder.build(instrumentName, event.timeframe());
            String htfBiasLabel = htfDecision != null ? htfDecision.bias().name() : null;
            Boolean structurePassed = structureDecision != null ? structureDecision.allows() : null;
            String structureReason = structureDecision != null ? structureDecision.reason().name() : null;
            enrichment = enrichment.withFilters(htfBiasLabel, structurePassed, structureReason);
            signal = signal.withEnrichment(enrichment);

            // Swing-bias filter (opt-in per (instrument, timeframe)). Null bias passes through.
            boolean filterRewroteToClose = false;
            if (state.swingBiasFilterEnabled()) {
                WtxAction filtered = WtxSwingBiasFilter.filter(
                        signal.direction(), signal.suggestedAction(),
                        enrichment.smcSwingBias(), state.currentPosition());
                if (filtered != signal.suggestedAction()) {
                    log.info("WTX [{} {}] swing-bias filter: direction={} swingBias={} action {} -> {}",
                            instrumentName, event.timeframe(), signal.direction(),
                            enrichment.smcSwingBias(), signal.suggestedAction(), filtered);
                    filterRewroteToClose =
                            filtered == WtxAction.CLOSE_LONG || filtered == WtxAction.CLOSE_SHORT;
                    signal = signal.withAction(filtered);
                }
            }

            if (signal.canTrade() && signal.suggestedAction() != WtxAction.NONE) {
                if (filterRewroteToClose) {
                    // Route BEFORE flattening so the bridge submits the full open quantity
                    // — applyAction → closePosition → withFlat clears entryQty to 0, which
                    // would shrink the IBKR close to a single contract for size > 1.
                    // Same ordering invariant as applyExit() and the MAX_LOSS_HALT path.
                    WtxRoutingResult closeRouting =
                            routeToExecution(signal, state, currentCandle.getClose());
                    signal = signal.withRouting(closeRouting);
                    // Skip the flatten if a prior entry is still resting unfilled — no broker order
                    // was sent, so keep the position side instead of marking it FLAT.
                    if (!skippedEntryInFlight(closeRouting)) {
                        state = applyAction(signal.suggestedAction(), state, instrument, config,
                                currentCandle.getClose(), null);
                    }
                } else {
                    BigDecimal entryAtr = AtrCalculator.compute(candles, config.atrLength());
                    WtxStrategyState preActionState = state;
                    state = applyAction(signal.suggestedAction(), state, instrument, config,
                            currentCandle.getClose(), entryAtr);
                    WtxRoutingResult routing = routeToExecution(signal, state, currentCandle.getClose());
                    signal = signal.withRouting(routing);
                    // Flatten-only reverse: the bridge flattened the prior position but skipped the
                    // open leg (unaffordable margin), so the broker is FLAT. applyAction already moved
                    // the virtual state to the new side — re-derive it as a plain close of the prior
                    // position so position/PnL bookkeeping matches the broker instead of tracking a
                    // phantom position that was never opened.
                    if (routing.outcome() == WtxRoutingOutcome.ROUTED_FLATTEN_ONLY) {
                        state = closePosition(preActionState, instrument, currentCandle.getClose());
                        log.warn("WTX [{} {}] reverse flattened only — virtual state corrected to FLAT "
                                + "(open leg skipped for margin)", instrumentName, event.timeframe());
                    } else if (routing.outcome() == WtxRoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT) {
                        // The bridge skipped the open: a prior entry order is still resting unfilled at
                        // the broker. No order was sent, so revert the optimistically-applied action —
                        // the only live order is the resting one, so the virtual state must keep pointing
                        // at its (pre-action) side, not the never-opened new side.
                        state = preActionState;
                        log.warn("WTX [{} {}] open/reverse skipped — prior entry still in flight; virtual "
                                + "state kept at pre-action side", instrumentName, event.timeframe());
                    }
                }
            }

            historyPort.save(signal);
            ws.convertAndSend("/topic/wtx-signals", toWsPayload(signal, state));
            publishWtxEvent(signal, currentCandle.getClose());
            log.info("WTX [{}] signal={} action={} canTrade={} wt1={} htf={} struct={}",
                    instrumentName, signal.signalType(), signal.suggestedAction(),
                    signal.canTrade(), signal.wt1Value(),
                    htfBiasLabel, structureReason);
        }

        // ── 4. Max-loss enforcement (profile >= SESSION_ATR) ──────────────────
        if (profile.blocksOnMaxLoss()
                && !state.maxLossHit()
                && WtxRiskGuard.isMaxLossHit(state, config.maxDailyLossUsd())) {
            log.warn("WTX [{}] daily max-loss hit (dailyPnl={}), flattening + halting", instrumentName, state.dailyPnl());
            boolean haltDeferred = false;
            if (state.currentPosition() != WtxPosition.FLAT) {
                WtxAction closeAction = state.currentPosition() == WtxPosition.LONG
                        ? WtxAction.CLOSE_LONG : WtxAction.CLOSE_SHORT;
                WtxSignal haltSignal = buildCloseSignal(state, event.timeframe(), closeAction,
                        "MAX_LOSS_HALT", event.timestamp());
                // Route BEFORE flattening so the bridge submits the full open quantity to IBKR.
                WtxRoutingResult haltRouting =
                        routeToExecution(haltSignal, state, currentCandle.getClose());
                haltSignal = haltSignal.withRouting(haltRouting);
                if (skippedEntryInFlight(haltRouting)) {
                    // Prior entry still resting UNFILLED and IBKR flat → no broker flatten was sent.
                    // Do NOT mark max-loss-hit: withMaxLossHit() forces the state FLAT and the retry
                    // gate (!state.maxLossHit()) would then lock out the flatten forever, leaving the
                    // entry to fill later as an unmanaged position. Defer — keep the position so the
                    // halt re-fires on a later bar once the entry fills (or the snapshot updates).
                    haltDeferred = true;
                    log.warn("WTX [{}] max-loss halt deferred — prior entry still in flight; "
                            + "position kept, will retry on a later bar", instrumentName);
                } else {
                    state = closePosition(state, instrument, currentCandle.getClose());
                }
                historyPort.save(haltSignal);
                ws.convertAndSend("/topic/wtx-signals", toWsPayload(haltSignal, state));
                publishWtxEvent(haltSignal, currentCandle.getClose());
            }
            // Only latch the halt (which also flattens the virtual state) when the flatten was not
            // deferred — otherwise the next bar must be free to retry the max-loss flatten.
            if (!haltDeferred) {
                state = state.withMaxLossHit();
            }
        }

        statePort.save(state.withLastCandleTs(event.timestamp()));
        publishState(state, config);
    }

    /** Called by the NY close scheduler — closes all open WTX positions across every timeframe. */
    public void forceCloseAll(String reason) {
        WtxConfig config = properties.toConfig();
        for (String instrumentName : config.instruments()) {
            for (String timeframe : config.timeframes()) {
                statePort.load(instrumentName, timeframe).ifPresent(state -> {
                    if (state.currentPosition() != WtxPosition.FLAT) {
                        try {
                            Instrument instrument = Instrument.valueOf(instrumentName);
                            List<Candle> candles = candlePort.findRecentCandles(instrument, timeframe, 1);
                            BigDecimal exitPrice = candles.isEmpty() ? state.entryPrice()
                                    : candles.get(0).getClose();
                            WtxAction closeAction = state.currentPosition() == WtxPosition.LONG
                                    ? WtxAction.CLOSE_LONG : WtxAction.CLOSE_SHORT;
                            WtxSignal forceCloseSignal = buildCloseSignal(state, timeframe, closeAction,
                                    "FORCE_CLOSE:" + reason, java.time.Instant.now());
                            // Route to IBKR with the pre-close state so the open quantity is preserved.
                            WtxRoutingResult fcRouting =
                                    routeToExecution(forceCloseSignal, state, exitPrice);
                            forceCloseSignal = forceCloseSignal.withRouting(fcRouting);
                            // Prior entry still resting unfilled → keep the position (no flatten sent).
                            WtxStrategyState closed = skippedEntryInFlight(fcRouting)
                                    ? state
                                    : closePosition(state, instrument, exitPrice);
                            historyPort.save(forceCloseSignal);
                            statePort.save(closed);
                            ws.convertAndSend("/topic/wtx-signals", toWsPayload(forceCloseSignal, closed));
                            publishWtxEvent(forceCloseSignal, exitPrice);
                            log.info("WTX [{} {}] force-closed — {}", instrumentName, timeframe, reason);
                            publishState(closed, config);
                        } catch (Exception e) {
                            log.error("WTX force-close failed for {} {}", instrumentName, timeframe, e);
                        }
                    }
                });
            }
        }
    }

    public Optional<WtxStrategyState> getState(String instrument, String timeframe) {
        return statePort.load(instrument, timeframe);
    }

    public List<WtxSignal> getRecentSignals(String instrument, int limit) {
        return historyPort.findRecent(instrument, limit);
    }

    public List<WtxSignal> getRecentSignals(String instrument, String timeframe, int limit) {
        return historyPort.findRecent(instrument, timeframe, limit);
    }

    public BigDecimal getMaxDailyLossUsd() {
        return properties.getMaxDailyLossUsd();
    }

    public BigDecimal getInitialEquity() {
        return properties.getInitialEquity();
    }

    /**
     * Effective protective stop for the open position (trailing level once armed,
     * else the derived initial ATR stop). Null when FLAT or ATR unavailable.
     * Surfaced on the state view so the panel shows the active risk level
     * immediately after a fresh fill.
     */
    public BigDecimal effectiveStop(WtxStrategyState state) {
        return WtxTrailingExitEvaluator.currentStop(state, properties.toConfig());
    }

    public WtxStrategyState updateProfile(String instrument, String timeframe, WtxProfile profile) {
        WtxStrategyState state = statePort.load(instrument, timeframe)
                .orElseGet(() -> WtxStrategyState.initial(instrument, timeframe, properties.getInitialEquity()));
        WtxStrategyState updated = state.withProfile(profile);
        statePort.save(updated);
        log.info("WTX [{} {}] profile updated to {}", instrument, timeframe, profile);
        publishState(updated, properties.toConfig());
        return updated;
    }

    public WtxStrategyState updateAutoExecution(String instrument, String timeframe, boolean enabled) {
        WtxStrategyState state = statePort.load(instrument, timeframe)
                .orElseGet(() -> WtxStrategyState.initial(instrument, timeframe, properties.getInitialEquity()));
        WtxStrategyState updated = state.withAutoExecution(enabled);
        statePort.save(updated);
        log.warn("WTX [{} {}] auto-execution {} — IBKR routing is now {}",
                instrument, timeframe, enabled ? "ENABLED" : "DISABLED",
                enabled ? "LIVE" : "off");
        publishState(updated, properties.toConfig());
        return updated;
    }

    public WtxStrategyState updateConfiguredOrderQty(String instrument, String timeframe, int qty) {
        WtxStrategyState state = statePort.load(instrument, timeframe)
                .orElseGet(() -> WtxStrategyState.initial(instrument, timeframe, properties.getInitialEquity()));
        WtxStrategyState updated = state.withConfiguredOrderQty(qty);
        statePort.save(updated);
        log.info("WTX [{} {}] configured order qty set to {} (sanitized: {})",
                instrument, timeframe, qty, updated.configuredOrderQty());
        publishState(updated, properties.toConfig());
        return updated;
    }

    public WtxStrategyState updateSwingBiasFilter(String instrument, String timeframe, boolean enabled) {
        WtxStrategyState state = statePort.load(instrument, timeframe)
                .orElseGet(() -> WtxStrategyState.initial(instrument, timeframe, properties.getInitialEquity()));
        WtxStrategyState updated = state.withSwingBiasFilter(enabled);
        statePort.save(updated);
        log.info("WTX [{} {}] swing-bias filter {}", instrument, timeframe,
                enabled ? "ENABLED" : "DISABLED");
        publishState(updated, properties.toConfig());
        return updated;
    }

    public WtxStrategyState updateTelegramNotifications(String instrument, String timeframe, boolean enabled) {
        WtxStrategyState state = statePort.load(instrument, timeframe)
                .orElseGet(() -> WtxStrategyState.initial(instrument, timeframe, properties.getInitialEquity()));
        WtxStrategyState updated = state.withTelegramNotifications(enabled);
        statePort.save(updated);
        log.info("WTX [{} {}] telegram notifications {}", instrument, timeframe,
                enabled ? "ENABLED" : "DISABLED");
        publishState(updated, properties.toConfig());
        return updated;
    }

    /**
     * Current swing bias for an (instrument, timeframe), derived from the most recent
     * signal's enrichment. Returns null when no signal exists yet or the enrichment did
     * not carry a bias (SMC engine warm-up). Used by the UI to display the direction
     * badge next to the filter toggle.
     */
    public String currentSwingBias(String instrument, String timeframe) {
        List<WtxSignal> recent = historyPort.findRecent(instrument, timeframe, 1);
        if (recent.isEmpty()) return null;
        WtxEnrichmentSnapshot enrichment = recent.get(0).enrichment();
        return enrichment != null ? enrichment.smcSwingBias() : null;
    }

    // ── private helpers ────────────────────────────────────────────────────

    private WtxStrategyState applyAction(WtxAction action, WtxStrategyState state,
                                         Instrument instrument, WtxConfig config,
                                         BigDecimal currentPrice, BigDecimal entryAtr) {
        // Panel-configured size wins over the global config.fixedQty so the UI quantity input
        // drives both the virtual P&L bookkeeping and the IBKR order quantity in one place.
        BigDecimal orderQty = BigDecimal.valueOf(state.configuredOrderQty());
        return switch (action) {
            case OPEN_LONG -> state.withPosition(WtxPosition.LONG, currentPrice, orderQty, entryAtr);
            case OPEN_SHORT -> state.withPosition(WtxPosition.SHORT, currentPrice, orderQty, entryAtr);
            case REVERSE_TO_LONG -> {
                WtxStrategyState closed = closePosition(state, instrument, currentPrice);
                yield closed.withPosition(WtxPosition.LONG, currentPrice, orderQty, entryAtr);
            }
            case REVERSE_TO_SHORT -> {
                WtxStrategyState closed = closePosition(state, instrument, currentPrice);
                yield closed.withPosition(WtxPosition.SHORT, currentPrice, orderQty, entryAtr);
            }
            case CLOSE_LONG, CLOSE_SHORT, CLOSE_ALL -> closePosition(state, instrument, currentPrice);
            default -> state;
        };
    }

    private WtxStrategyState applyExit(WtxStrategyState state, Instrument instrument,
                                       WtxTrailingExitEvaluator.Decision exit, CandleClosed event,
                                       WtxProfile profile) {
        WtxSignal exitSignal = buildCloseSignal(state, event.timeframe(), exit.exitAction(),
                exit.reason().name(), event.timestamp());
        // Route to IBKR BEFORE flattening so the bridge sees the open quantity.
        // (closePosition → withFlat clears entryQty to 0, which would shrink the IBKR close to 1 contract.)
        WtxRoutingResult routing = routeToExecution(exitSignal, state, exit.exitPrice());
        exitSignal = exitSignal.withRouting(routing);
        // Prior entry still resting unfilled → no broker flatten sent; keep the position so the
        // protective stop stays armed until the entry fills (or a later bar reconciles).
        WtxStrategyState closed = skippedEntryInFlight(routing)
                ? state
                : closePosition(state, instrument, exit.exitPrice());
        historyPort.save(exitSignal);
        ws.convertAndSend("/topic/wtx-signals", toWsPayload(exitSignal, closed));
        publishWtxEvent(exitSignal, exit.exitPrice());
        log.info("WTX [{}] trailing exit fired — reason={} exitPrice={} mfe={}",
                state.instrument(), exit.reason(), exit.exitPrice(), exit.updatedBestFavorablePrice());
        return closed;
    }

    private WtxSignal buildCloseSignal(WtxStrategyState state, String timeframe, WtxAction action,
                                       String reason, java.time.Instant ts) {
        boolean wasLong = state.currentPosition() == WtxPosition.LONG;
        return new WtxSignal(
                state.instrument(),
                timeframe,
                wasLong ? WtxSignalType.VENTA : WtxSignalType.COMPRA,
                wasLong ? "SHORT" : "LONG",
                BigDecimal.ZERO, BigDecimal.ZERO,
                true,
                action,
                WtxEnrichmentSnapshot.empty().withFilters(null, null, reason),
                ts,
                null,
                null
        );
    }

    private WtxStrategyState closePosition(WtxStrategyState state, Instrument instrument, BigDecimal exitPrice) {
        if (state.currentPosition() == WtxPosition.FLAT || state.entryPrice() == null) {
            return state;
        }
        Side side = state.currentPosition() == WtxPosition.LONG ? Side.LONG : Side.SHORT;
        int qty = state.entryQty() != null ? state.entryQty().intValue() : 1;
        BigDecimal pnl = instrument.calculatePnL(state.entryPrice(), exitPrice, qty, side);
        WtxStrategyState flat = state.withFlat(pnl);
        // Auto-execution: the close ORDER may rest then cancel/expire unfilled, so book the realized P&L
        // PENDING — WtxClosePnlSettler rolls it back if the close never completes (and finalizes it once the
        // broker confirms). Paper closes are immediate/real, so nothing is pending.
        return state.autoExecutionEnabled() ? flat.withPendingClose(pnl) : flat;
    }

    private WtxHtfBiasFilter.HtfBiasContext buildHtfContext(Instrument instrument, WtxConfig config) {
        try {
            int needed = Math.max(config.htfFastLen(), config.htfSlowLen()) + 10;
            List<Candle> htfCandles = new java.util.ArrayList<>(
                    candlePort.findRecentCandles(instrument, config.htfTimeframe(), needed));
            java.util.Collections.reverse(htfCandles);
            if (htfCandles.size() < config.htfSlowLen() + 1) {
                return new WtxHtfBiasFilter.HtfBiasContext(null, null, null);
            }
            BigDecimal htfClose = htfCandles.get(htfCandles.size() - 1).getClose();
            BigDecimal fastEma = new EMAIndicator(config.htfFastLen()).current(htfCandles);
            BigDecimal slowEma = new EMAIndicator(config.htfSlowLen()).current(htfCandles);
            return new WtxHtfBiasFilter.HtfBiasContext(htfClose, fastEma, slowEma);
        } catch (Exception e) {
            log.debug("WTX HTF fetch failed for {}: {}", instrument, e.getMessage());
            return new WtxHtfBiasFilter.HtfBiasContext(null, null, null);
        }
    }

    private WtxRoutingResult routeToExecution(WtxSignal signal, WtxStrategyState state,
                                              BigDecimal referencePrice) {
        if (!state.autoExecutionEnabled()) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_AUTO_OFF);
        }
        WtxExecutionBridge bridge = executionBridgeProvider.getIfAvailable();
        if (bridge == null) {
            log.info("WTX [{} {}] auto-execution enabled but bridge not wired",
                    state.instrument(), state.timeframe());
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_BRIDGE_UNAVAILABLE);
        }
        try {
            return bridge.submit(signal, state, referencePrice);
        } catch (Exception e) {
            log.error("WTX [{} {}] execution bridge failure: {}",
                    state.instrument(), state.timeframe(), e.getMessage(), e);
            String msg = e.getMessage() == null ? "execution bridge failure" : e.getMessage();
            return WtxRoutingResult.of(WtxRoutingOutcome.FAILED,
                    msg.length() > 200 ? msg.substring(0, 200) : msg);
        }
    }

    /**
     * True when the bridge skipped an open/close because our own prior entry order is still resting
     * UNFILLED at the broker. No broker order was sent, so the caller must NOT mutate the virtual
     * state (neither apply the new action nor flatten) — the only live order is the resting entry,
     * so the panel keeps pointing at its side until it fills or a later bar reconciles.
     */
    private static boolean skippedEntryInFlight(WtxRoutingResult routing) {
        return routing != null && routing.outcome() == WtxRoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT;
    }

    private void publishState(WtxStrategyState state, WtxConfig config) {
        try {
            ws.convertAndSend("/topic/wtx-state/" + state.instrument() + "/" + state.timeframe(),
                    toStatePayload(state, config));
        } catch (Exception e) {
            log.debug("WTX WebSocket publish failed", e);
        }
    }

    private Map<String, Object> toWsPayload(WtxSignal signal, WtxStrategyState state) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("instrument", signal.instrument());
        payload.put("timeframe", signal.timeframe());
        payload.put("signalType", signal.signalType().name());
        payload.put("direction", signal.direction());
        payload.put("wt1Value", signal.wt1Value());
        payload.put("wt2Value", signal.wt2Value());
        payload.put("canTrade", signal.canTrade());
        payload.put("actionTaken", signal.suggestedAction().name());
        payload.put("enrichment", signal.enrichment());
        payload.put("signalTs", signal.signalTs().toString());
        payload.put("routingOutcome", signal.routingOutcome() != null ? signal.routingOutcome().name() : null);
        payload.put("routingErrorMessage", signal.routingErrorMessage());
        return payload;
    }

    private Map<String, Object> toStatePayload(WtxStrategyState state, WtxConfig config) {
        WtxProfile profile = state.activeProfile() != null ? state.activeProfile() : WtxProfile.BASELINE;
        Map<String, Object> payload = new HashMap<>();
        payload.put("instrument", state.instrument());
        payload.put("timeframe", state.timeframe());
        payload.put("currentDirection", state.currentPosition().name());
        payload.put("dailyPnl", state.dailyPnl());
        payload.put("dayStartEquity", state.dayStartEquity());
        payload.put("maxDailyLossUsd", config.maxDailyLossUsd());
        payload.put("maxLossHit", state.maxLossHit());
        payload.put("activeProfile", profile.name());
        payload.put("autoExecutionEnabled", state.autoExecutionEnabled());
        payload.put("swingBiasFilterEnabled", state.swingBiasFilterEnabled());
        payload.put("currentSwingBias", currentSwingBias(state.instrument(), state.timeframe()));
        payload.put("configuredOrderQty", state.configuredOrderQty());
        payload.put("entryPrice", state.entryPrice());
        payload.put("entryQty", state.entryQty());
        payload.put("stopLoss", WtxTrailingExitEvaluator.currentStop(state, config));
        payload.put("canTrade", !state.maxLossHit() || !profile.blocksOnMaxLoss());
        return payload;
    }
}
