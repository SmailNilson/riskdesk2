package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.indicators.AtrCalculator;
import com.riskdesk.domain.engine.indicators.BollingerBandsIndicator;
import com.riskdesk.domain.engine.indicators.EMAIndicator;
import com.riskdesk.domain.engine.indicators.MarketRegimeDetector;
import com.riskdesk.domain.engine.indicators.WaveTrendIndicator;
import com.riskdesk.domain.engine.indicators.WaveTrendIndicator.WaveTrendResult;
import com.riskdesk.domain.engine.strategy.wtx.*;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxParamOverridePort;
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
    private final WtxParamOverridePort paramOverridePort;

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
            WtxPositionReconciler positionReconciler,
            WtxParamOverridePort paramOverridePort
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
        this.paramOverridePort = paramOverridePort;
    }

    /**
     * Apply per-(instrument,timeframe) frontend overrides on top of a base config: each non-null
     * override replaces the corresponding global value, others fall back. Idempotent (re-applying
     * the same override yields the same config), so it is safe to call on an already-effective config.
     */
    private WtxConfig applyOverrides(WtxConfig base, WtxParamOverride ov) {
        if (ov == null || ov.isEmpty()) return base;
        int n1 = ov.n1() != null ? ov.n1() : base.n1();
        int n2 = ov.n2() != null ? ov.n2() : base.n2();
        int sig = ov.signalPeriod() != null ? ov.signalPeriod() : base.signalPeriod();
        WtxConfig eff = base.withIndicatorParams(n1, n2, sig);
        if (ov.slAtrMult() != null) {
            eff = eff.withSlAtrMult(ov.slAtrMult());
        }
        if (ov.nsc() != null || ov.nsv() != null || ov.useCompra1() != null || ov.useVenta1() != null) {
            eff = eff.withSignalZone(
                    ov.nsc() != null ? ov.nsc() : eff.nsc(),
                    ov.nsv() != null ? ov.nsv() : eff.nsv(),
                    ov.useCompra1() != null ? ov.useCompra1() : eff.useCompra1(),
                    ov.useVenta1() != null ? ov.useVenta1() : eff.useVenta1());
        }
        // Per-panel session-filter toggle (boundaries stay global) — lets the top-train-Z35 variant
        // trade around the clock (its validated shape) while legacy panels keep the 03:00-08:00 ET block.
        if (ov.sessionFilterEnabled() != null) {
            eff = eff.withSessionFilter(ov.sessionFilterEnabled(),
                    eff.sessionBlockStartMinEt(), eff.sessionBlockEndMinEt());
        }
        return eff;
    }

    /**
     * Base config for a panel BEFORE its stored per-panel override: the global config for a
     * legacy panel, or global + the variant's named preset for a variant panel (e.g.
     * {@code top-train-Z35} on panel key {@code 10m-z35}). Stored overrides still apply on top,
     * so the operator can tweak a variant panel from the UI like any other.
     */
    private WtxConfig baseConfigFor(String instrument, String panelKey) {
        WtxConfig base = properties.toConfig();
        return variantFor(instrument, panelKey)
                .flatMap(v -> WtxParamOverride.preset(v.getPreset()))
                .map(preset -> applyOverrides(base, preset))
                .orElse(base);
    }

    /** The configured variant behind this panel key, if any. */
    private Optional<WtxStrategyProperties.Variant> variantFor(String instrument, String panelKey) {
        return variants().stream()
                .filter(v -> v.getInstrument().equals(instrument) && v.getPanelKey().equals(panelKey))
                .findFirst();
    }

    /** Timeframe whose CANDLES feed this panel — the base timeframe for a variant, else the key itself. */
    private String dataTimeframe(String instrument, String panelKey) {
        return variantFor(instrument, panelKey)
                .map(WtxStrategyProperties.Variant::getBaseTimeframe)
                .orElse(panelKey);
    }

    /** Configured variant panels — internal, null-safe view of the properties list. */
    private List<WtxStrategyProperties.Variant> variants() {
        List<WtxStrategyProperties.Variant> variants = properties.getVariants();
        return variants == null ? List.of() : variants;
    }

    /**
     * Transport-friendly view of a configured variant panel — keeps the presentation layer off
     * the infrastructure config type (hexagonal rule: presentation ↛ infrastructure).
     */
    public record WtxVariantView(String name, String instrument, String baseTimeframe,
                                 String preset, String panelKey) {}

    /** Configured variant panels (name, instrument, base timeframe, preset, panel key). Never null. */
    public List<WtxVariantView> getVariants() {
        return variants().stream()
                .map(v -> new WtxVariantView(v.getName(), v.getInstrument(), v.getBaseTimeframe(),
                        v.getPreset(), v.getPanelKey()))
                .toList();
    }

    /** Panel's base config (global, or global+preset for a variant) with its stored overrides applied. */
    public WtxConfig effectiveConfig(String instrument, String timeframe) {
        return applyOverrides(baseConfigFor(instrument, timeframe), paramOverridePort.load(instrument, timeframe));
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
        WtxConfig global = properties.toConfig();
        // Legacy panel — identity == data timeframe, bit-for-bit the historical behaviour.
        if (global.instruments().contains(event.instrument())
                && global.timeframes().contains(event.timeframe())) {
            processPanel(event, event.timeframe());
        }
        // Variant panels riding the same closed candle (e.g. top-train-Z35 on MNQ 10m → key 10m-z35):
        // same candle data, but their own state / signals / overrides under the panel key, and a
        // base config seeded from the variant's named preset.
        for (WtxStrategyProperties.Variant variant : variants()) {
            if (variant.getInstrument().equals(event.instrument())
                    && variant.getBaseTimeframe().equals(event.timeframe())) {
                processPanel(event, variant.getPanelKey());
            }
        }
    }

    /**
     * Evaluate one panel for a closed candle. {@code panelKey} is the panel's IDENTITY (state,
     * signal history, overrides, WS topics); candle data always comes from the event's (base)
     * timeframe. For legacy panels {@code panelKey == event.timeframe()}.
     */
    private void processPanel(CandleClosed event, String panelKey) {
        String instrumentName = event.instrument();

        // Panel base config (global, or global+preset for a variant) with the panel's stored
        // overrides applied. From here on `config` is the EFFECTIVE config — the WaveTrend
        // construction and the trailing-exit evaluator below both read from it.
        WtxConfig config = applyOverrides(baseConfigFor(instrumentName, panelKey),
                paramOverridePort.load(instrumentName, panelKey));

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

        WtxStrategyState state = statePort.load(instrumentName, panelKey)
                .orElseGet(() -> WtxStrategyState.initial(instrumentName, panelKey,
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
        // close); no-op when paper / aligned / a close is still pending (the settler owns that window). The
        // bar ATR gives a re-adopted position an ATR basis so ATR-exit profiles keep a protective trailing.
        BigDecimal reconcileAtr = AtrCalculator.compute(candles, config.atrLength());
        state = positionReconciler.reconcile(state, instrument, currentCandle.getClose(), reconcileAtr);

        // ── 1. Trailing exit check (profile >= SESSION_ATR) ───────────────────
        if (profile.requiresAtrExits() && state.currentPosition() != WtxPosition.FLAT) {
            WtxTrailingExitEvaluator.Decision exit =
                    WtxTrailingExitEvaluator.evaluate(state, currentCandle, config);
            if (exit.shouldExit()) {
                state = applyExit(state, instrument, exit, event, panelKey, profile);
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
                prev, curr, config, state, event.timestamp(), panelKey, null, null);

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

        // Side we hold going into the signal block — lets the HTF-bias exit (§3b) tell a position
        // that merely RODE this bar apart from one the signal block just opened / reversed / closed.
        WtxPosition posBeforeSignal = state.currentPosition();

        // ── 3. Final signal evaluation with filter decisions ──────────────────
        Optional<WtxSignal> maybeSignal = WtxBarEvaluator.evaluate(
                prev, curr, config, state, event.timestamp(), panelKey,
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
                            instrumentName, panelKey, signal.direction(),
                            enrichment.smcSwingBias(), signal.suggestedAction(), filtered);
                    filterRewroteToClose =
                            filtered == WtxAction.CLOSE_LONG || filtered == WtxAction.CLOSE_SHORT;
                    signal = signal.withAction(filtered);
                }
            }

            // Capture the day's realized P&L BEFORE any close so we can stamp the per-trade delta below.
            BigDecimal realizedBefore = state.dailyRealizedPnl();
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
                                + "(open leg skipped for margin)", instrumentName, panelKey);
                    } else if (routing.outcome() == WtxRoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT) {
                        // The bridge skipped the open: a prior entry order is still resting unfilled at
                        // the broker. No order was sent, so revert the optimistically-applied action —
                        // the only live order is the resting one, so the virtual state must keep pointing
                        // at its (pre-action) side, not the never-opened new side.
                        state = preActionState;
                        log.warn("WTX [{} {}] open/reverse skipped — prior entry still in flight; virtual "
                                + "state kept at pre-action side", instrumentName, panelKey);
                    }
                }
            }

            // Tag the close kind so the UI distinguishes a reverse from a TP/SL/force exit.
            if (filterRewroteToClose) {
                signal = signal.withExitType(WtxExitType.SWING_BIAS);
            } else if (signal.suggestedAction() == WtxAction.REVERSE_TO_LONG
                    || signal.suggestedAction() == WtxAction.REVERSE_TO_SHORT) {
                signal = signal.withExitType(WtxExitType.REVERSE);
            }
            // Stamp the realized P&L this signal booked (delta of the day's realized P&L across the
            // close). Non-zero only when a position actually closed — opens / skipped closes leave it null.
            BigDecimal realizedDelta = state.dailyRealizedPnl().subtract(realizedBefore);
            if (realizedDelta.signum() != 0) {
                signal = signal.withRealizedPnl(realizedDelta);
            }
            signal = signal.withPrice(currentCandle.getClose());
            historyPort.save(signal);
            ws.convertAndSend("/topic/wtx-signals", toWsPayload(signal, state));
            publishWtxEvent(signal, currentCandle.getClose());
            log.info("WTX [{}] signal={} action={} canTrade={} wt1={} htf={} struct={}",
                    instrumentName, signal.signalType(), signal.suggestedAction(),
                    signal.canTrade(), signal.wt1Value(),
                    htfBiasLabel, structureReason);
        }

        // ── 3b. HTF-bias early exit ("A2", opt-in) ────────────────────────────
        // Close an open position the 1h bias no longer SUPPORTS (turned NEUTRAL or opposite) — on top
        // of the normal SL / opposite-WT reverse. Fires only when (a) opted in via the global flag,
        // (b) the HTF context was built (HTF profile), and (c) the signal block above did NOT touch the
        // position this bar (same side we entered the bar with). A freshly opened / reversed position
        // already passed the HTF gate, so re-checking it would be a no-op; the guard also avoids a
        // double close after a §3 reverse/close. Real-1m MNQ 10m backtest: +60% net, WR 32%->46%.
        if (properties.isHtfBiasExitEnabled()
                && htfCtx != null
                && state.currentPosition() != WtxPosition.FLAT
                && state.currentPosition() == posBeforeSignal) {
            WtxHtfBiasFilter.HtfBias bias = WtxHtfBiasFilter.evaluate("LONG", htfCtx).bias();
            if (WtxHtfBiasExitEvaluator.shouldExit(state.currentPosition(), bias)) {
                WtxAction closeAction = state.currentPosition() == WtxPosition.LONG
                        ? WtxAction.CLOSE_LONG : WtxAction.CLOSE_SHORT;
                WtxSignal biasExit = buildCloseSignal(state, panelKey, closeAction,
                        "HTF_BIAS_EXIT:" + bias.name(), WtxExitType.HTF_BIAS, event.timestamp());
                // Route BEFORE flattening so the bridge submits the full open quantity (same ordering
                // invariant as applyExit() / MAX_LOSS_HALT — withFlat clears entryQty to 0 otherwise).
                WtxRoutingResult biasRouting = routeToExecution(biasExit, state, currentCandle.getClose());
                biasExit = biasExit.withRouting(biasRouting);
                BigDecimal biasRealizedBefore = state.dailyRealizedPnl();
                // Skip the flatten if a prior entry is still resting unfilled — no broker order was sent.
                if (!skippedEntryInFlight(biasRouting)) {
                    state = closePosition(state, instrument, currentCandle.getClose());
                }
                BigDecimal biasRealizedDelta = state.dailyRealizedPnl().subtract(biasRealizedBefore);
                if (biasRealizedDelta.signum() != 0) {
                    biasExit = biasExit.withRealizedPnl(biasRealizedDelta);
                }
                biasExit = biasExit.withPrice(currentCandle.getClose());
                historyPort.save(biasExit);
                ws.convertAndSend("/topic/wtx-signals", toWsPayload(biasExit, state));
                publishWtxEvent(biasExit, currentCandle.getClose());
                log.info("WTX [{} {}] HTF-bias early exit — pos={} bias={} closed",
                        instrumentName, panelKey, posBeforeSignal, bias);
            }
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
                WtxSignal haltSignal = buildCloseSignal(state, panelKey, closeAction,
                        "MAX_LOSS_HALT", WtxExitType.MAX_LOSS, event.timestamp());
                // Route BEFORE flattening so the bridge submits the full open quantity to IBKR.
                WtxRoutingResult haltRouting =
                        routeToExecution(haltSignal, state, currentCandle.getClose());
                haltSignal = haltSignal.withRouting(haltRouting);
                BigDecimal haltRealizedBefore = state.dailyRealizedPnl();
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
                BigDecimal haltRealizedDelta = state.dailyRealizedPnl().subtract(haltRealizedBefore);
                if (haltRealizedDelta.signum() != 0) {
                    haltSignal = haltSignal.withRealizedPnl(haltRealizedDelta);
                }
                haltSignal = haltSignal.withPrice(currentCandle.getClose());
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

    /** Called by the NY close scheduler — closes all open WTX positions across every panel (variants included). */
    public void forceCloseAll(String reason) {
        WtxConfig config = properties.toConfig();
        for (String instrumentName : config.instruments()) {
            for (String timeframe : config.timeframes()) {
                forceClosePanel(instrumentName, timeframe, timeframe, reason, config);
            }
        }
        // Variant panels hold their own positions — they must flatten at the NY close too.
        for (WtxStrategyProperties.Variant variant : variants()) {
            forceClosePanel(variant.getInstrument(), variant.getPanelKey(), variant.getBaseTimeframe(),
                    reason, config);
        }
    }

    private void forceClosePanel(String instrumentName, String panelKey, String dataTimeframe,
                                 String reason, WtxConfig config) {
        statePort.load(instrumentName, panelKey).ifPresent(state -> {
            if (state.currentPosition() != WtxPosition.FLAT) {
                try {
                    Instrument instrument = Instrument.valueOf(instrumentName);
                    List<Candle> candles = candlePort.findRecentCandles(instrument, dataTimeframe, 1);
                    BigDecimal exitPrice = candles.isEmpty() ? state.entryPrice()
                            : candles.get(0).getClose();
                    WtxAction closeAction = state.currentPosition() == WtxPosition.LONG
                            ? WtxAction.CLOSE_LONG : WtxAction.CLOSE_SHORT;
                    WtxSignal forceCloseSignal = buildCloseSignal(state, panelKey, closeAction,
                            "FORCE_CLOSE:" + reason, WtxExitType.FORCE_CLOSE, java.time.Instant.now());
                    // Route to IBKR with the pre-close state so the open quantity is preserved.
                    WtxRoutingResult fcRouting =
                            routeToExecution(forceCloseSignal, state, exitPrice);
                    forceCloseSignal = forceCloseSignal.withRouting(fcRouting);
                    // Prior entry still resting unfilled → keep the position (no flatten sent).
                    WtxStrategyState closed = skippedEntryInFlight(fcRouting)
                            ? state
                            : closePosition(state, instrument, exitPrice);
                    BigDecimal fcRealizedDelta = closed.dailyRealizedPnl().subtract(state.dailyRealizedPnl());
                    if (fcRealizedDelta.signum() != 0) {
                        forceCloseSignal = forceCloseSignal.withRealizedPnl(fcRealizedDelta);
                    }
                    forceCloseSignal = forceCloseSignal.withPrice(exitPrice);
                    historyPort.save(forceCloseSignal);
                    statePort.save(closed);
                    ws.convertAndSend("/topic/wtx-signals", toWsPayload(forceCloseSignal, closed));
                    publishWtxEvent(forceCloseSignal, exitPrice);
                    log.info("WTX [{} {}] force-closed — {}", instrumentName, panelKey, reason);
                    publishState(closed, config);
                } catch (Exception e) {
                    log.error("WTX force-close failed for {} {}", instrumentName, panelKey, e);
                }
            }
        });
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
        return WtxTrailingExitEvaluator.currentStop(state, effectiveConfig(state.instrument(), state.timeframe()));
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
     * Override the WaveTrend periods for this (instrument, timeframe) panel. Any null arg clears that
     * override (falls back to the global config value). Stored separately from {@link WtxStrategyState};
     * applied per-bar in onCandleClosed. Returns the (unchanged) runtime state so the panel refreshes.
     */
    public WtxStrategyState updateIndicatorParams(String instrument, String timeframe,
                                                  Integer n1, Integer n2, Integer signalPeriod) {
        WtxParamOverride cur = paramOverridePort.load(instrument, timeframe);
        // Preserve the SL, zone and session overrides — this endpoint only edits the WaveTrend periods.
        WtxParamOverride next = new WtxParamOverride(n1, n2, signalPeriod, cur.slAtrMult(),
                cur.nsc(), cur.nsv(), cur.useCompra1(), cur.useVenta1(), cur.sessionFilterEnabled());
        paramOverridePort.save(instrument, timeframe, next);
        log.info("WTX [{} {}] indicator params override -> n1={} n2={} signalPeriod={}",
                instrument, timeframe, n1, n2, signalPeriod);
        WtxStrategyState state = statePort.load(instrument, timeframe)
                .orElseGet(() -> WtxStrategyState.initial(instrument, timeframe, properties.getInitialEquity()));
        publishState(state, effectiveConfig(instrument, timeframe));
        return state;
    }

    /**
     * Override the initial-stop ATR multiple for this (instrument, timeframe) panel. A null arg clears
     * the override (falls back to the global {@code slAtrMult}). Returns the runtime state so the panel refreshes.
     */
    public WtxStrategyState updateSlAtrMult(String instrument, String timeframe, BigDecimal slAtrMult) {
        WtxParamOverride cur = paramOverridePort.load(instrument, timeframe);
        // Preserve the period, zone and session overrides — this endpoint only edits the SL multiple.
        WtxParamOverride next = new WtxParamOverride(cur.n1(), cur.n2(), cur.signalPeriod(), slAtrMult,
                cur.nsc(), cur.nsv(), cur.useCompra1(), cur.useVenta1(), cur.sessionFilterEnabled());
        paramOverridePort.save(instrument, timeframe, next);
        log.info("WTX [{} {}] SL ATR-mult override -> {}", instrument, timeframe, slAtrMult);
        WtxStrategyState state = statePort.load(instrument, timeframe)
                .orElseGet(() -> WtxStrategyState.initial(instrument, timeframe, properties.getInitialEquity()));
        publishState(state, effectiveConfig(instrument, timeframe));
        return state;
    }

    /**
     * Override the session entry filter for this (instrument, timeframe) panel. {@code true}/{@code false}
     * forces the gate on/off regardless of the global config; a null arg clears the override (falls back
     * to the global session filter). Only the on/off gate is per-panel — the blocked window itself stays
     * the global one (currently 03:00-08:00 ET). Exits are never gated; entries only. Returns the runtime
     * state so the panel refreshes.
     */
    public WtxStrategyState updateSessionFilter(String instrument, String timeframe, Boolean enabled) {
        WtxParamOverride cur = paramOverridePort.load(instrument, timeframe);
        // Preserve the period, SL and zone overrides — this endpoint only edits the session gate.
        WtxParamOverride next = new WtxParamOverride(cur.n1(), cur.n2(), cur.signalPeriod(), cur.slAtrMult(),
                cur.nsc(), cur.nsv(), cur.useCompra1(), cur.useVenta1(), enabled);
        paramOverridePort.save(instrument, timeframe, next);
        log.info("WTX [{} {}] session filter override -> {}", instrument, timeframe, enabled);
        WtxStrategyState state = statePort.load(instrument, timeframe)
                .orElseGet(() -> WtxStrategyState.initial(instrument, timeframe, properties.getInitialEquity()));
        publishState(state, effectiveConfig(instrument, timeframe));
        return state;
    }

    /**
     * Apply a complete named override preset (e.g. {@link WtxParamOverride#TOP_TRAIN_Z35}) to this
     * (instrument, timeframe) panel in one atomic save — periods, SL multiple AND signal-zone
     * gating together. Applying {@link WtxParamOverride#NONE} clears every override (back to the
     * global config). Returns the runtime state so the panel refreshes.
     */
    public WtxStrategyState applyPreset(String instrument, String timeframe, WtxParamOverride preset) {
        WtxParamOverride effective = preset == null ? WtxParamOverride.NONE : preset;
        paramOverridePort.save(instrument, timeframe, effective);
        log.info("WTX [{} {}] override preset applied -> {}", instrument, timeframe, effective);
        WtxStrategyState state = statePort.load(instrument, timeframe)
                .orElseGet(() -> WtxStrategyState.initial(instrument, timeframe, properties.getInitialEquity()));
        publishState(state, effectiveConfig(instrument, timeframe));
        return state;
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

    /**
     * Current market regime (TRENDING_UP/DOWN, RANGING, CHOPPY) for an (instrument, timeframe),
     * from EMA9/50/200 alignment + Bollinger-width expansion on the most recent candles. Surfaced
     * on the state view as an INFORMATIONAL badge — the analysis showed WTX bleeds in TRENDING, so
     * the UI warns the operator. It does NOT gate trading (HTF already filters trend direction).
     * Returns null when the instrument is unknown or there isn't enough history yet.
     */
    public String currentRegime(String instrument, String timeframe) {
        try {
            Instrument inst = Instrument.valueOf(instrument);
            // Variant panels have no candles under their own key — read the base timeframe's data.
            List<Candle> candles = new java.util.ArrayList<>(
                    candlePort.findRecentCandles(inst, dataTimeframe(instrument, timeframe), 220));
            java.util.Collections.reverse(candles); // chronological (oldest → newest)
            if (candles.size() < 210) return null;   // need EMA200 + a little warm-up
            BigDecimal ema9 = new EMAIndicator(9).current(candles);
            BigDecimal ema50 = new EMAIndicator(50).current(candles);
            BigDecimal ema200 = new EMAIndicator(200).current(candles);
            BollingerBandsIndicator.BBTrendResult bb = new BollingerBandsIndicator().currentTrend(candles);
            boolean expanding = bb != null && bb.expanding();
            return new MarketRegimeDetector().detect(ema9, ema50, ema200, expanding);
        } catch (Exception e) {
            log.debug("WTX regime compute failed for {} {}: {}", instrument, timeframe, e.getMessage());
            return null;
        }
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
                                       String panelKey, WtxProfile profile) {
        WtxSignal exitSignal = buildCloseSignal(state, panelKey, exit.exitAction(),
                exit.reason().name(), WtxExitType.fromExitReason(exit.reason()), event.timestamp());
        // Route to IBKR BEFORE flattening so the bridge sees the open quantity.
        // (closePosition → withFlat clears entryQty to 0, which would shrink the IBKR close to 1 contract.)
        WtxRoutingResult routing = routeToExecution(exitSignal, state, exit.exitPrice());
        exitSignal = exitSignal.withRouting(routing);
        // Prior entry still resting unfilled → no broker flatten sent; keep the position so the
        // protective stop stays armed until the entry fills (or a later bar reconciles).
        WtxStrategyState closed = skippedEntryInFlight(routing)
                ? state
                : closePosition(state, instrument, exit.exitPrice());
        BigDecimal realizedDelta = closed.dailyRealizedPnl().subtract(state.dailyRealizedPnl());
        if (realizedDelta.signum() != 0) {
            exitSignal = exitSignal.withRealizedPnl(realizedDelta);
        }
        exitSignal = exitSignal.withPrice(exit.exitPrice());
        historyPort.save(exitSignal);
        ws.convertAndSend("/topic/wtx-signals", toWsPayload(exitSignal, closed));
        publishWtxEvent(exitSignal, exit.exitPrice());
        log.info("WTX [{}] trailing exit fired — reason={} exitPrice={} mfe={}",
                state.instrument(), exit.reason(), exit.exitPrice(), exit.updatedBestFavorablePrice());
        return closed;
    }

    private WtxSignal buildCloseSignal(WtxStrategyState state, String timeframe, WtxAction action,
                                       String reason, WtxExitType exitType, java.time.Instant ts) {
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
                null,
                null,
                exitType,
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
        payload.put("price", signal.price());
        // Mirror toSignalView() — without this the live-streamed close arrives with no exitType and the
        // panel's first-wins de-dup keeps it over the REST copy, so the TP/SL badge never renders live.
        payload.put("exitType", signal.exitType() != null ? signal.exitType().name() : null);
        // Same de-dup concern for realizedPnl — the live close must carry it so the per-day P&L total
        // is correct before the next REST poll arrives.
        payload.put("realizedPnl", signal.realizedPnl());
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
        payload.put("regime", currentRegime(state.instrument(), state.timeframe()));
        payload.put("configuredOrderQty", state.configuredOrderQty());
        // Effective WaveTrend periods + SL ATR-mult (global config with this panel's overrides applied).
        payload.put("n1", config.n1());
        payload.put("n2", config.n2());
        payload.put("signalPeriod", config.signalPeriod());
        payload.put("slAtrMult", config.slAtrMult());
        // Effective signal-zone gating (global config + this panel's overrides / preset).
        payload.put("nsc", config.nsc());
        payload.put("nsv", config.nsv());
        payload.put("zoneOnlyEntries", !config.useCompra1() && !config.useVenta1());
        payload.put("sessionFilterEnabled", config.sessionFilterEnabled());
        payload.put("entryPrice", state.entryPrice());
        payload.put("entryQty", state.entryQty());
        payload.put("stopLoss", WtxTrailingExitEvaluator.currentStop(state, config));
        payload.put("canTrade", !state.maxLossHit() || !profile.blocksOnMaxLoss());
        return payload;
    }
}
