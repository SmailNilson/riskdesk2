package com.riskdesk.application.service;

import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import com.riskdesk.domain.alert.model.AlertSeverity;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.correlation.CorrelationState;
import com.riskdesk.domain.engine.correlation.CrossInstrumentCorrelationEngine;
import com.riskdesk.domain.engine.correlation.CrossInstrumentSignal;
import com.riskdesk.domain.engine.indicators.IntrabarBreakoutDetector;
import com.riskdesk.domain.engine.indicators.VWAPIndicator;
import com.riskdesk.domain.engine.indicators.VwapRejectionDetector;
import com.riskdesk.domain.marketdata.event.CandleClosed;
import com.riskdesk.domain.marketdata.event.MarketPriceUpdated;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.TradingSessionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-layer orchestrator for the Oil-Nasdaq Inverse Momentum Scalp (ONIMS) strategy.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Listens to {@link CandleClosed} events for MCL (5m) and MNQ (5m).</li>
 *   <li>Loads recent candles from {@link CandleRepositoryPort}, runs domain detectors,
 *       and feeds signals into the thread-safe {@link CrossInstrumentCorrelationEngine}.</li>
 *   <li>Applies session, VIX, and OPEC-blackout filters before triggering the engine.</li>
 *   <li>Publishes confirmed {@link CrossInstrumentSignal}s to WebSocket topic
 *       {@code /topic/correlation-alerts} and buffers history for REST consumers.</li>
 *   <li>Caches VIX=F live price from {@link MarketPriceUpdated} events to avoid a
 *       dedicated IBKR subscription query on each candle evaluation.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * The {@link CrossInstrumentCorrelationEngine} is thread-safe internally.
 * This service's own mutable state ({@link #signalHistory}, {@link #vixPrice},
 * {@link #config}) is protected by appropriate primitives (volatile / ConcurrentHashMap
 * / synchronized). The {@link CandleRepositoryPort} calls are read-only and stateless.
 *
 * <h2>VIX Filter — Architect Note</h2>
 * Per the Lead Tech review: use {@code VIX=F} futures via IBKR rather than computing a
 * custom implied-volatility proxy. This service caches the latest VIX=F price received
 * via {@link MarketPriceUpdated} (instrument name "VIX"). If no VIX price has been
 * received yet (subscription not active), the filter fails-open to avoid blocking signals.
 *
 * <h2>Session Filter</h2>
 * Only fires during high-liquidity NY windows: 09:30–11:30 ET and 13:30–15:30 ET.
 * Uses {@link TradingSessionResolver#CME_ZONE} for DST-safe time comparisons.
 */
@Service
public class CrossInstrumentAlertService {

    private static final Logger log = LoggerFactory.getLogger(CrossInstrumentAlertService.class);

    // WebSocket topic for cross-instrument signals
    static final String TOPIC_CORRELATION = "/topic/correlation-alerts";

    // Session windows (America/New_York) — high-liquidity NY periods only
    private static final LocalTime SESSION_AM_OPEN  = LocalTime.of(9,  30);
    private static final LocalTime SESSION_AM_CLOSE = LocalTime.of(11, 30);
    private static final LocalTime SESSION_PM_OPEN  = LocalTime.of(13, 30);
    private static final LocalTime SESSION_PM_CLOSE = LocalTime.of(15, 30);

    // VIX threshold for the regime filter
    private static final double DEFAULT_VIX_THRESHOLD = 20.0;

    // Candles loaded per evaluation cycle (lookback + 1 for current)
    private static final int CANDLE_LOAD_COUNT = 25; // 25 × 5m = 2h context

    // Maximum signal history kept in memory (REST endpoint)
    private static final int MAX_HISTORY = 100;

    // -----------------------------------------------------------------------
    // Dependencies
    // -----------------------------------------------------------------------

    private final CandleRepositoryPort          candlePort;
    private final SimpMessagingTemplate         messagingTemplate;
    private final CrossInstrumentCorrelationEngine engine;
    private final IntrabarBreakoutDetector      breakoutDetector;
    private final VwapRejectionDetector         rejectionDetector;
    private final VWAPIndicator                 vwapIndicator;

    // -----------------------------------------------------------------------
    // Mutable state — protected per field (see comments)
    // -----------------------------------------------------------------------

    /** Latest VIX=F price received via MarketPriceUpdated. {@code null} = not yet received. */
    private volatile BigDecimal vixPrice;

    /**
     * Optional: UTC start of an OPEC+/EIA announcement blackout window.
     * Signals are suppressed for {@code blackoutDurationMinutes} after this instant.
     */
    private volatile Instant blackoutStart;
    private volatile int     blackoutDurationMinutes = 20;

    /** Configurable VIX threshold. */
    private volatile double vixThreshold = DEFAULT_VIX_THRESHOLD;

    /** Recent signal history — synchronized(signalHistory) for all accesses. */
    private final LinkedList<Map<String, Object>> signalHistory = new LinkedList<>();

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public CrossInstrumentAlertService(CandleRepositoryPort candlePort,
                                       SimpMessagingTemplate messagingTemplate) {
        this.candlePort        = candlePort;
        this.messagingTemplate = messagingTemplate;
        this.engine            = new CrossInstrumentCorrelationEngine();
        this.breakoutDetector  = new IntrabarBreakoutDetector();
        this.rejectionDetector = new VwapRejectionDetector();
        this.vwapIndicator     = new VWAPIndicator();
    }

    // -----------------------------------------------------------------------
    // Event Listeners
    // -----------------------------------------------------------------------

    /**
     * Handles every closed candle. Dispatches to MCL or MNQ evaluation paths.
     * Only acts on 5-minute bars — ignored for all other timeframes.
     */
    @EventListener
    public void onCandleClosed(CandleClosed event) {
        if (!"5m".equals(event.timeframe())) return;

        // Run timeout check on every 5m close to evict stale MCL triggers
        engine.checkTimeout(event.timestamp());

        try {
            switch (event.instrument()) {
                case "MCL" -> evaluateMclBreakout(event.timestamp());
                case "MNQ" -> evaluateMnqRejection(event.timestamp());
                default    -> { /* other instruments — no-op */ }
            }
        } catch (Exception e) {
            log.debug("ONIMS evaluation error for {} 5m: {}", event.instrument(), e.getMessage());
        }
    }

    /**
     * Caches VIX=F price ticks for the volatility regime filter.
     * Instrument name expected from IBKR: "VIX".
     */
    @EventListener
    public void onMarketPriceUpdated(MarketPriceUpdated event) {
        if ("VIX".equals(event.instrument())) {
            vixPrice = event.price();
            log.trace("VIX=F cached: {}", event.price());
        }
    }

    // -----------------------------------------------------------------------
    // Evaluation Logic
    // -----------------------------------------------------------------------

    /**
     * Evaluates whether the latest MCL 5m candle constitutes a channel breakout.
     * If confirmed and all filters pass, transitions the engine to MCL_TRIGGERED.
     */
    private void evaluateMclBreakout(Instant candleTimestamp) {
        // 1. Pre-checks: only fire when engine is IDLE
        if (engine.currentState() != CorrelationState.IDLE) return;

        // 2. Session filter
        if (!isWithinTradingSession(candleTimestamp)) return;

        // 3. Blackout filter
        if (isInBlackout(candleTimestamp)) {
            log.debug("ONIMS: MCL evaluation skipped — announcement blackout active");
            return;
        }

        // 4. VIX regime filter — fail-open if no VIX data received yet
        if (!passesVixFilter()) return;

        // 5. Load recent MCL 5m candles
        List<Candle> candles = candlePort.findRecentCandles(Instrument.MCL, "5m", CANDLE_LOAD_COUNT);
        if (candles.isEmpty()) return;

        // 6. Run breakout detector
        Optional<IntrabarBreakoutDetector.BreakoutResult> result = breakoutDetector.detect(candles);
        result.ifPresent(breakout -> {
            boolean triggered = engine.onMclBreakout(
                    candleTimestamp,
                    breakout.breakoutClose(),
                    breakout.resistanceLevel()
            );
            if (triggered) {
                log.info("ONIMS: MCL breakout detected — close={} above resistance={} vol_ratio={:.2f}",
                        breakout.breakoutClose(), breakout.resistanceLevel(), breakout.volumeRatio());
            }
        });
    }

    /**
     * Evaluates whether the latest MNQ 5m candle constitutes a bearish VWAP rejection
     * that completes the ONIMS correlation pattern.
     */
    private void evaluateMnqRejection(Instant candleTimestamp) {
        // Only meaningful when MCL trigger is active
        if (engine.currentState() != CorrelationState.MCL_TRIGGERED) return;

        // Blackout filter
        if (isInBlackout(candleTimestamp)) return;

        // Load recent MNQ 5m candles for VWAP computation
        List<Candle> candles = candlePort.findRecentCandles(Instrument.MNQ, "5m", CANDLE_LOAD_COUNT);
        if (candles.size() < 2) return;

        // Compute VWAP from session-start
        VWAPIndicator.VWAPResult vwapResult = vwapIndicator.current(candles);
        if (vwapResult == null || vwapResult.vwap() == null) return;

        BigDecimal vwap = vwapResult.vwap();
        Candle latest   = candles.get(candles.size() - 1);

        // Run VWAP rejection detector
        VwapRejectionDetector.RejectionResult rejection = rejectionDetector.detect(latest, vwap);
        if (rejection == null) return;

        // Attempt to confirm the signal in the engine
        Optional<CrossInstrumentSignal> signal = engine.onMnqVwapRejection(
                candleTimestamp, vwap, latest.getClose()
        );

        signal.ifPresent(s -> {
            log.info("ONIMS: CONFIRMED — lag={}s MCL_close={} MNQ_close={} VWAP={} dist={:.3f}%",
                    s.lagSeconds(), s.leaderBreakoutPrice(), s.followerClosePrice(),
                    s.followerVwap(), rejection.distanceBelowPct());
            publishSignal(s);
        });
    }

    // -----------------------------------------------------------------------
    // Filter Helpers
    // -----------------------------------------------------------------------

    /** Returns true if {@code ts} falls within the NY AM or PM high-liquidity session windows. */
    private boolean isWithinTradingSession(Instant ts) {
        ZonedDateTime zdt = ts.atZone(TradingSessionResolver.CME_ZONE);
        LocalTime time    = zdt.toLocalTime();
        boolean inAm = !time.isBefore(SESSION_AM_OPEN) && time.isBefore(SESSION_AM_CLOSE);
        boolean inPm = !time.isBefore(SESSION_PM_OPEN) && time.isBefore(SESSION_PM_CLOSE);
        return inAm || inPm;
    }

    /**
     * Returns true if the VIX regime filter passes.
     * Fails-open (returns true) when no VIX data has been received yet,
     * so that the primary MCL/MNQ conditions still operate.
     */
    private boolean passesVixFilter() {
        BigDecimal cached = vixPrice;
        if (cached == null) {
            log.trace("ONIMS: VIX=F not yet received — VIX filter bypassed");
            return true; // fail-open
        }
        boolean passes = cached.doubleValue() >= vixThreshold;
        if (!passes) {
            log.debug("ONIMS: VIX={} below threshold={} — signal suppressed", cached, vixThreshold);
        }
        return passes;
    }

    /** Returns true if {@code ts} falls within an active announcement blackout window. */
    private boolean isInBlackout(Instant ts) {
        Instant start = blackoutStart;
        if (start == null) return false;
        long elapsedMinutes = java.time.Duration.between(start, ts).toMinutes();
        return elapsedMinutes >= 0 && elapsedMinutes < blackoutDurationMinutes;
    }

    // -----------------------------------------------------------------------
    // Signal Publishing
    // -----------------------------------------------------------------------

    private void publishSignal(CrossInstrumentSignal signal) {
        Map<String, Object> payload = toPayload(signal);
        synchronized (signalHistory) {
            signalHistory.addFirst(payload);
            while (signalHistory.size() > MAX_HISTORY) signalHistory.removeLast();
        }
        try {
            messagingTemplate.convertAndSend(TOPIC_CORRELATION, payload);
        } catch (Exception e) {
            log.debug("ONIMS: WebSocket publish failed: {}", e.getMessage());
        }
    }

    private Map<String, Object> toPayload(CrossInstrumentSignal s) {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("type",               "ONIMS");
        map.put("category",           AlertCategory.CROSS_INSTRUMENT.name());
        map.put("severity",           AlertSeverity.WARNING.name());
        map.put("leaderInstrument",   s.leaderInstrument());
        map.put("followerInstrument", s.followerInstrument());
        map.put("leaderBreakout",     s.leaderBreakoutPrice());
        map.put("leaderResistance",   s.leaderResistanceLevel());
        map.put("followerVwap",       s.followerVwap());
        map.put("followerClose",      s.followerClosePrice());
        map.put("lagSeconds",         s.lagSeconds());
        map.put("confirmedAt",        s.confirmedAt().toString());
        map.put("message", String.format(
                "SHORT MNQ — MCL broke %.2f (res=%.2f), MNQ rejected VWAP %.2f — lag %ds",
                s.leaderBreakoutPrice(), s.leaderResistanceLevel(),
                s.followerVwap(), s.lagSeconds()));
        return map;
    }

    // -----------------------------------------------------------------------
    // Configuration & Status API (called by REST controller)
    // -----------------------------------------------------------------------

    /** Returns the current engine state. */
    public CorrelationState currentState() {
        return engine.currentState();
    }

    /** Returns recent signal history (newest first). */
    public List<Map<String, Object>> getSignalHistory() {
        synchronized (signalHistory) {
            return Collections.unmodifiableList(new LinkedList<>(signalHistory));
        }
    }

    /** Clears the signal history and resets the engine. */
    public void reset() {
        engine.forceReset();
        synchronized (signalHistory) {
            signalHistory.clear();
        }
        log.info("ONIMS: engine and history reset");
    }

    /**
     * Activates an announcement blackout window starting now for the configured duration.
     * Call this before a known OPEC+ or EIA release.
     */
    public void activateBlackout() {
        blackoutStart = Instant.now();
        log.info("ONIMS: announcement blackout activated for {} minutes", blackoutDurationMinutes);
    }

    public void setVixThreshold(double threshold) {
        this.vixThreshold = threshold;
        log.info("ONIMS: VIX threshold updated to {}", threshold);
    }

    public void setBlackoutDurationMinutes(int minutes) {
        this.blackoutDurationMinutes = minutes;
    }

    public double getVixThreshold() {
        return vixThreshold;
    }

    public BigDecimal getCachedVixPrice() {
        return vixPrice;
    }

    public Instant getBlackoutStart() {
        return blackoutStart;
    }

    public int getBlackoutDurationMinutes() {
        return blackoutDurationMinutes;
    }

    public boolean isBlackoutActive() {
        return isInBlackout(Instant.now());
    }
}
