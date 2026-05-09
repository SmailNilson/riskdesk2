package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.indicators.WaveTrendIndicator;
import com.riskdesk.domain.engine.indicators.WaveTrendIndicator.WaveTrendResult;
import com.riskdesk.domain.engine.strategy.wtx.*;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxSignalHistoryPort;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxStrategyStatePort;
import com.riskdesk.domain.marketdata.event.CandleClosed;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.infrastructure.config.WtxStrategyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrator for the WaveTrend XT strategy.
 * Completely independent from AlertService, SignalConfluenceBuffer, and MentorSignalReviewService.
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

    public WtxStrategyService(
            WtxStrategyStatePort statePort,
            WtxSignalHistoryPort historyPort,
            CandleRepositoryPort candlePort,
            WtxEnrichmentBuilder enrichmentBuilder,
            SimpMessagingTemplate ws,
            WtxStrategyProperties properties
    ) {
        this.statePort = statePort;
        this.historyPort = historyPort;
        this.candlePort = candlePort;
        this.enrichmentBuilder = enrichmentBuilder;
        this.ws = ws;
        this.properties = properties;
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

        WtxStrategyState state = statePort.load(instrumentName)
                .orElseGet(() -> WtxStrategyState.initial(instrumentName, properties.getInitialEquity()));

        // Day-change detection (America/New_York)
        if (WtxRiskGuard.isNewTradingDay(state.lastCandleTs(), event.timestamp())) {
            BigDecimal equity = state.currentEquity();
            state = state.withDayReset(equity);
            log.info("WTX [{}] new trading day — equity reset to {}", instrumentName, equity);
        }

        // Evaluate signal
        Optional<WtxSignal> maybeSignal = WtxBarEvaluator.evaluate(prev, curr, config, state, event.timestamp(), event.timeframe());

        if (maybeSignal.isPresent()) {
            WtxSignal signal = maybeSignal.get();

            // Attach enrichment (informative only)
            WtxEnrichmentSnapshot enrichment = enrichmentBuilder.build(instrumentName, event.timeframe());
            signal = signal.withEnrichment(enrichment);

            // Apply action if canTrade
            if (signal.canTrade()) {
                state = applyAction(signal.suggestedAction(), state, instrument, config,
                        candles.get(candles.size() - 1).getClose());
            }

            historyPort.save(signal);
            ws.convertAndSend("/topic/wtx-signals", toWsPayload(signal, state));
            log.info("WTX [{}] signal={} action={} canTrade={} wt1={}",
                    instrumentName, signal.signalType(), signal.suggestedAction(),
                    signal.canTrade(), signal.wt1Value());
        }

        statePort.save(state.withLastCandleTs(event.timestamp()));
        publishState(state, config);
    }

    /** Called by the NY close scheduler — closes all open WTX positions. */
    public void forceCloseAll(String reason) {
        WtxConfig config = properties.toConfig();
        for (String instrumentName : config.instruments()) {
            statePort.load(instrumentName).ifPresent(state -> {
                if (state.currentPosition() != WtxPosition.FLAT) {
                    try {
                        Instrument instrument = Instrument.valueOf(instrumentName);
                        // Use shortest timeframe for the most recent exit price
                        String shortestTf = config.timeframes().get(0);
                        List<Candle> candles = candlePort.findRecentCandles(instrument, shortestTf, 1);
                        BigDecimal exitPrice = candles.isEmpty() ? state.entryPrice()
                                : candles.get(0).getClose();
                        WtxStrategyState closed = closePosition(state, instrument, exitPrice);
                        statePort.save(closed);
                        log.info("WTX [{}] force-closed — {}", instrumentName, reason);
                        publishState(closed, config);
                    } catch (Exception e) {
                        log.error("WTX force-close failed for {}", instrumentName, e);
                    }
                }
            });
        }
    }

    public Optional<WtxStrategyState> getState(String instrument) {
        return statePort.load(instrument);
    }

    public List<WtxSignal> getRecentSignals(String instrument, int limit) {
        return historyPort.findRecent(instrument, limit);
    }

    public List<WtxSignal> getRecentSignals(String instrument, String timeframe, int limit) {
        return historyPort.findRecent(instrument, timeframe, limit);
    }

    public java.math.BigDecimal getMaxDailyLossUsd() {
        return properties.getMaxDailyLossUsd();
    }

    public java.math.BigDecimal getInitialEquity() {
        return properties.getInitialEquity();
    }

    // ── private helpers ────────────────────────────────────────────────────

    private WtxStrategyState applyAction(WtxAction action, WtxStrategyState state,
                                         Instrument instrument, WtxConfig config,
                                         BigDecimal currentPrice) {
        return switch (action) {
            case OPEN_LONG -> state.withPosition(WtxPosition.LONG, currentPrice, config.fixedQty());
            case OPEN_SHORT -> state.withPosition(WtxPosition.SHORT, currentPrice, config.fixedQty());
            case REVERSE_TO_LONG -> {
                WtxStrategyState closed = closePosition(state, instrument, currentPrice);
                yield closed.withPosition(WtxPosition.LONG, currentPrice, config.fixedQty());
            }
            case REVERSE_TO_SHORT -> {
                WtxStrategyState closed = closePosition(state, instrument, currentPrice);
                yield closed.withPosition(WtxPosition.SHORT, currentPrice, config.fixedQty());
            }
            default -> state;
        };
    }

    private WtxStrategyState closePosition(WtxStrategyState state, Instrument instrument, BigDecimal exitPrice) {
        if (state.currentPosition() == WtxPosition.FLAT || state.entryPrice() == null) {
            return state;
        }
        Side side = state.currentPosition() == WtxPosition.LONG ? Side.LONG : Side.SHORT;
        int qty = state.entryQty() != null ? state.entryQty().intValue() : 1;
        BigDecimal pnl = instrument.calculatePnL(state.entryPrice(), exitPrice, qty, side);
        return state.withFlat(pnl);
    }

    private void publishState(WtxStrategyState state, WtxConfig config) {
        try {
            ws.convertAndSend("/topic/wtx-state/" + state.instrument(), toStatePayload(state, config));
        } catch (Exception e) {
            log.debug("WTX WebSocket publish failed", e);
        }
    }

    private java.util.Map<String, Object> toWsPayload(WtxSignal signal, WtxStrategyState state) {
        return java.util.Map.of(
                "instrument", signal.instrument(),
                "timeframe", signal.timeframe(),
                "signalType", signal.signalType().name(),
                "direction", signal.direction(),
                "wt1Value", signal.wt1Value(),
                "wt2Value", signal.wt2Value(),
                "canTrade", signal.canTrade(),
                "actionTaken", signal.suggestedAction().name(),
                "enrichment", signal.enrichment(),
                "signalTs", signal.signalTs().toString()
        );
    }

    private java.util.Map<String, Object> toStatePayload(WtxStrategyState state, WtxConfig config) {
        return java.util.Map.of(
                "instrument", state.instrument(),
                "currentDirection", state.currentPosition().name(),
                "dailyPnl", state.dailyPnl(),
                "dayStartEquity", state.dayStartEquity(),
                "maxDailyLossUsd", config.maxDailyLossUsd(),
                "maxLossHit", state.maxLossHit(),
                "canTrade", true
        );
    }
}
