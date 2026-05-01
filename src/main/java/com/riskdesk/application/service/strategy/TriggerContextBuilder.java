package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.strategy.detector.ReactionPatternDetector;
import com.riskdesk.domain.engine.strategy.model.DeltaSignature;
import com.riskdesk.domain.engine.strategy.model.DomSignal;
import com.riskdesk.domain.engine.strategy.model.ReactionPattern;
import com.riskdesk.domain.engine.strategy.model.TickDataQuality;
import com.riskdesk.domain.engine.strategy.model.TriggerContext;
import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.MarketSnapshot;
import com.riskdesk.domain.quant.model.QuantState;
import com.riskdesk.domain.quant.pattern.OrderFlowPatternDetector;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Produces a {@link TriggerContext} for one (instrument, timeframe) evaluation.
 *
 * <p><b>Source preference</b> (best to worst):
 * <ol>
 *   <li>{@code REAL_TICKS} — Lee-Ready-classified tick aggregation from
 *       {@link TickDataPort}. Used when the IBKR tick-by-tick subscription is
 *       active for the instrument.</li>
 *   <li>{@code CLV_ESTIMATED} — buy/sell ratio approximated from the latest
 *       candle's Close-Location-Value carried on {@link IndicatorSnapshot}.</li>
 *   <li>{@code UNAVAILABLE} — neither path produced a number; downstream agents
 *       discount accordingly.</li>
 * </ol>
 *
 * <p>The port can be null in test setups that do not exercise real-tick paths;
 * the builder transparently falls back to CLV in that case.
 */
@Component
public class TriggerContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(TriggerContextBuilder.class);

    /**
     * Candle window for reaction classification. We only look at the latest closed
     * candle today, but leave room to grow into 2- or 3-bar patterns without
     * changing the repository call shape.
     */
    private static final int REACTION_CANDLE_LIMIT = 3;

    /** buyRatioPct (0..100) above this threshold = "heavy buying". */
    private static final double HEAVY_BUY_PCT = 65.0;
    /** buyRatioPct (0..100) below this threshold = "heavy selling". */
    private static final double HEAVY_SELL_PCT = 35.0;

    /** Number of recent candle closes fed to the order-flow pattern detector. Matches
     *  the {@code QuantSetupNarrationService.PRICE_HISTORY_DEPTH} default so both
     *  systems see the same 3-tick window when classifying the regime. */
    private static final int PATTERN_PRICE_HISTORY_DEPTH = 3;

    /** Trading-day projection used by the pattern detector — the 4-quadrant
     *  classifier itself ignores the date, but {@link QuantState#reset(LocalDate)}
     *  insists on a non-null value, so we just pin to America/New_York like the
     *  rest of the system. */
    private static final ZoneId NY = ZoneId.of("America/New_York");

    private final CandleRepositoryPort candleRepository;
    private final TickDataPort tickDataPort;
    private final OrderFlowPatternDetector patternDetector;

    @Autowired
    public TriggerContextBuilder(CandleRepositoryPort candleRepository,
                                  TickDataPort tickDataPort,
                                  OrderFlowPatternDetector patternDetector) {
        this.candleRepository = candleRepository;
        this.tickDataPort = tickDataPort;
        this.patternDetector = patternDetector;
    }

    /**
     * Test/back-compat constructor — wires the candle repository and tick port,
     * defaults the pattern detector to a fresh {@link OrderFlowPatternDetector}.
     * Pre-existing tests that constructed the builder before the pattern field
     * was added keep working without modification.
     */
    public TriggerContextBuilder(CandleRepositoryPort candleRepository, TickDataPort tickDataPort) {
        this(candleRepository, tickDataPort, new OrderFlowPatternDetector());
    }

    /**
     * Test/back-compat constructor — wires only the candle repository. Real-tick
     * lookups are skipped (port is null) and the pattern detector is the default,
     * so the builder behaves exactly like the pre-tick implementation but still
     * publishes a precomputed {@link PatternAnalysis} on the resulting
     * {@link TriggerContext}.
     */
    public TriggerContextBuilder(CandleRepositoryPort candleRepository) {
        this(candleRepository, null, new OrderFlowPatternDetector());
    }

    public TriggerContext build(Instrument instrument, String timeframe,
                                 IndicatorSnapshot snapshot) {
        Optional<TickAggregation> realAgg = lookupRealTicks(instrument);
        ReactionPattern reaction = classifyReaction(instrument, timeframe);
        // DOM is not yet on the snapshot — report UNAVAILABLE. A later slice wires
        // MarketDepthPort here.
        DomSignal dom = DomSignal.UNAVAILABLE;
        // Pattern is computed from delta + recent close prices (same window the
        // Quant scheduler uses), so it survives the real-tick / CLV / unavailable
        // branches identically.
        PatternAnalysis pattern = computeOrderFlowPattern(instrument, timeframe, snapshot, realAgg);

        if (realAgg.isPresent()) {
            return fromRealTicks(realAgg.get(), reaction, dom, pattern);
        }
        return fromSnapshot(snapshot, reaction, dom, pattern);
    }

    /**
     * Back-compat shim — keeps the old 1-arg signature so tests / callers that
     * don't need reaction detection still work. The shim reports no reaction
     * (equivalent to {@link ReactionPattern#NONE}) and does not consult ticks.
     * Pattern computation is skipped (no instrument/timeframe context to fetch
     * candles from) so the resulting context carries a null pattern; downstream
     * agents abstain accordingly.
     */
    public TriggerContext build(IndicatorSnapshot snapshot) {
        return fromSnapshot(snapshot, ReactionPattern.NONE, DomSignal.UNAVAILABLE, null);
    }

    private Optional<TickAggregation> lookupRealTicks(Instrument instrument) {
        if (tickDataPort == null) return Optional.empty();
        try {
            return tickDataPort.currentAggregation(instrument)
                .filter(agg -> TickAggregation.SOURCE_REAL_TICKS.equals(agg.source()))
                .filter(TriggerContextBuilder::hasUsableVolume);
        } catch (RuntimeException e) {
            // The port is infra; a transient hiccup must never sink the strategy
            // evaluation. Degrade silently to the CLV path.
            log.debug("Tick aggregation lookup failed for {}: {}", instrument, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Reject {@code REAL_TICKS} snapshots whose rolling window is empty.
     *
     * <p>{@code TickByTickAggregator.snapshot()} calls {@code evictExpired()} before
     * computing, so an instrument that traded earlier but has gone quiet for the
     * full window is reported with {@code buyVolume=0, sellVolume=0,
     * buyRatioPct=0.0} — and the source flag stays {@code REAL_TICKS}. Without
     * this filter the classifier would read 0% buy ratio as a strong bearish FLOW,
     * generating a false trigger vote during quiet periods or feed gaps.
     *
     * <p>This is distinct from a "all sells" snapshot: if {@code sellVolume > 0
     * && buyVolume == 0}, total volume is non-zero, the ratio is genuinely 0.0,
     * and the bearish FLOW vote is correct. We discriminate on total volume, not
     * on the ratio.
     */
    private static boolean hasUsableVolume(TickAggregation agg) {
        return (agg.buyVolume() + agg.sellVolume()) > 0L;
    }

    private TriggerContext fromRealTicks(TickAggregation agg, ReactionPattern reaction, DomSignal dom,
                                          PatternAnalysis pattern) {
        BigDecimal buyRatio = BigDecimal.valueOf(agg.buyRatioPct() / 100.0)
            .setScale(4, RoundingMode.HALF_UP);
        BigDecimal cumulative = BigDecimal.valueOf(agg.cumulativeDelta());
        DeltaSignature signature = classifyFromTicks(agg);
        return new TriggerContext(signature, buyRatio, cumulative, dom, reaction,
            TickDataQuality.REAL_TICKS, pattern);
    }

    private TriggerContext fromSnapshot(IndicatorSnapshot snapshot, ReactionPattern reaction,
                                         DomSignal dom, PatternAnalysis pattern) {
        BigDecimal buyRatio = snapshot.buyRatio();
        BigDecimal cumulativeDelta = snapshot.cumulativeDelta();
        TickDataQuality quality = inferClvQuality(snapshot);
        DeltaSignature signature = classifyFromSnapshot(snapshot, buyRatio);
        return new TriggerContext(signature, buyRatio, cumulativeDelta, dom, reaction, quality, pattern);
    }

    /**
     * Builds the {@link PatternAnalysis} from the same data the legacy
     * {@code DeltaFlowTriggerAgent} consumed (cumulative delta + last price)
     * but routed through the Quant 4-quadrant classifier so the result is
     * stable across consecutive scans.
     *
     * <p>Returns {@code null} when essential inputs are missing — the new
     * {@code QuantFlowPatternAgent} treats null as abstain rather than failing.
     */
    private PatternAnalysis computeOrderFlowPattern(Instrument instrument, String timeframe,
                                                     IndicatorSnapshot snapshot,
                                                     Optional<TickAggregation> realAgg) {
        Double delta = pickDelta(snapshot, realAgg);
        if (delta == null) return null;

        List<Double> recent = recentCloses(instrument, timeframe);
        // Resolved via if/else rather than a ternary — mixing a primitive double
        // (snapshot.lastPrice().doubleValue()) with a possibly-null Double
        // (recent.get(...)) triggers Java auto-unboxing on the result type, which
        // NPEs when the snapshot has no price AND no candles are available.
        final Double currentPrice;
        if (snapshot != null && snapshot.lastPrice() != null) {
            currentPrice = snapshot.lastPrice().doubleValue();
        } else if (!recent.isEmpty()) {
            currentPrice = recent.get(recent.size() - 1);
        } else {
            currentPrice = null;
        }

        MarketSnapshot ms = new MarketSnapshot.Builder()
            .now(Instant.now())
            .price(currentPrice)
            .delta(delta)
            .build();
        QuantState state = QuantState.reset(LocalDate.now(NY));
        try {
            return patternDetector.detect(ms, state, recent);
        } catch (RuntimeException e) {
            log.debug("Pattern detection failed for {} {}: {}", instrument, timeframe, e.getMessage());
            return null;
        }
    }

    private static Double pickDelta(IndicatorSnapshot snapshot, Optional<TickAggregation> realAgg) {
        if (realAgg.isPresent()) {
            return (double) realAgg.get().cumulativeDelta();
        }
        if (snapshot != null && snapshot.cumulativeDelta() != null) {
            return snapshot.cumulativeDelta().doubleValue();
        }
        return null;
    }

    private List<Double> recentCloses(Instrument instrument, String timeframe) {
        try {
            List<Candle> candles = candleRepository.findRecentCandles(
                instrument, timeframe, PATTERN_PRICE_HISTORY_DEPTH);
            // findRecentCandles returns newest-first; the pattern detector wants
            // newest-last (oldest → newest), same convention as classifyReaction.
            List<Candle> ascending = new ArrayList<>(candles);
            Collections.reverse(ascending);
            List<Double> closes = new ArrayList<>(ascending.size());
            for (Candle c : ascending) {
                if (c.getClose() != null) closes.add(c.getClose().doubleValue());
            }
            return closes;
        } catch (Exception e) {
            log.debug("Recent-close lookup failed for {} {}: {}",
                instrument, timeframe, e.getMessage());
            return List.of();
        }
    }

    private ReactionPattern classifyReaction(Instrument instrument, String timeframe) {
        try {
            List<Candle> candles = candleRepository.findRecentCandles(
                instrument, timeframe, REACTION_CANDLE_LIMIT);
            // IMPORTANT: CandleRepositoryPort.findRecentCandles returns candles in
            // DESCENDING timestamp order (newest first — see
            // JpaCandleRepositoryAdapter.findRecentCandles). ReactionPatternDetector
            // assumes ASCENDING order (newest last), consistent with the convention
            // used by IndicatorService.loadCandles. Reverse here so the detector
            // classifies the newest candle — otherwise it would read the
            // (limit-1)-bars-stale candle and systematically delay / mis-fire the
            // reaction-trigger vote.
            List<Candle> ascending = new ArrayList<>(candles);
            Collections.reverse(ascending);
            return ReactionPatternDetector.classifyLatest(ascending);
        } catch (Exception e) {
            log.debug("Reaction detection failed for {} {}: {}",
                instrument, timeframe, e.getMessage());
            return ReactionPattern.NONE;
        }
    }

    private static TickDataQuality inferClvQuality(IndicatorSnapshot s) {
        // The snapshot doesn't carry provenance; the order-flow source is currently
        // CLV-estimated when ticks are unavailable. Report CLV when we have any
        // delta value, UNAVAILABLE otherwise.
        return (s.buyRatio() != null || s.cumulativeDelta() != null)
            ? TickDataQuality.CLV_ESTIMATED
            : TickDataQuality.UNAVAILABLE;
    }

    /**
     * Real-tick classifier. Order matters:
     * <ol>
     *   <li>Divergence (price up + delta down, or inverse) → ABSORPTION — strongest
     *       signal because Lee-Ready ticks let us see the imbalance the candle
     *       hides.</li>
     *   <li>buyRatio outside [HEAVY_SELL_PCT, HEAVY_BUY_PCT] → FLOW — orderly
     *       continuation in the dominant direction.</li>
     *   <li>Otherwise NEUTRAL.</li>
     * </ol>
     * EXHAUSTION is left out of this slice — it requires a "post-push" context the
     * tick window alone cannot capture; the SMC layer would need to flag a recent
     * BOS first.
     */
    private static DeltaSignature classifyFromTicks(TickAggregation agg) {
        if (agg.divergenceDetected()) return DeltaSignature.ABSORPTION;
        double pct = agg.buyRatioPct();
        if (pct >= HEAVY_BUY_PCT || pct <= HEAVY_SELL_PCT) return DeltaSignature.FLOW;
        return DeltaSignature.NEUTRAL;
    }

    private static DeltaSignature classifyFromSnapshot(IndicatorSnapshot s, BigDecimal buyRatio) {
        if (buyRatio == null && s.cumulativeDelta() == null) return DeltaSignature.NEUTRAL;
        double r = buyRatio == null ? 0.5 : buyRatio.doubleValue();
        boolean heavyBuyers = r > 0.65;
        boolean heavySellers = r < 0.35;

        // Crude heuristic — the real ABSORPTION classifier belongs to a tick-stream
        // aware detector (now wired in classifyFromTicks). Here: heavy one-sided
        // delta without a corresponding EMA9 push suggests absorption.
        if (heavySellers && s.ema9() != null && s.ema50() != null
            && s.ema9().compareTo(s.ema50()) >= 0) {
            return DeltaSignature.ABSORPTION;  // sellers heavy, price holds → buyers absorbing
        }
        if (heavyBuyers && s.ema9() != null && s.ema50() != null
            && s.ema9().compareTo(s.ema50()) <= 0) {
            return DeltaSignature.ABSORPTION;  // buyers heavy, price holds → sellers absorbing
        }
        if (heavyBuyers || heavySellers) return DeltaSignature.FLOW;
        return DeltaSignature.NEUTRAL;
    }
}
