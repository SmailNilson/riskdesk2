package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.strategy.detector.ReactionPatternDetector;
import com.riskdesk.domain.engine.strategy.model.DeltaSignature;
import com.riskdesk.domain.engine.strategy.model.DomSignal;
import com.riskdesk.domain.engine.strategy.model.ReactionPattern;
import com.riskdesk.domain.engine.strategy.model.TickDataQuality;
import com.riskdesk.domain.engine.strategy.model.TriggerContext;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Produces a {@link TriggerContext} from the indicator snapshot — the cheap path.
 * A future iteration will read from the tick stream directly (see
 * {@code TickDataPort}); for now we approximate using CLV-based delta already in
 * the snapshot. Quality is reported accurately so downstream agents know to
 * discount.
 */
@Component
public class TriggerContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(TriggerContextBuilder.class);

    /** |cumulativeDelta| above this fraction of volume is "heavy" — tunable. */
    private static final double HEAVY_DELTA_RATIO = 0.3;

    /**
     * Candle window for reaction classification. We only look at the latest closed
     * candle today, but leave room to grow into 2- or 3-bar patterns without
     * changing the repository call shape.
     */
    private static final int REACTION_CANDLE_LIMIT = 3;

    private final CandleRepositoryPort candleRepository;

    public TriggerContextBuilder(CandleRepositoryPort candleRepository) {
        this.candleRepository = candleRepository;
    }

    public TriggerContext build(Instrument instrument, String timeframe,
                                 IndicatorSnapshot snapshot) {
        BigDecimal buyRatio = snapshot.buyRatio();
        BigDecimal cumulativeDelta = snapshot.cumulativeDelta();
        TickDataQuality quality = inferQuality(snapshot);

        DeltaSignature signature = classify(snapshot, buyRatio, cumulativeDelta);
        // DOM is not yet on the snapshot — report UNAVAILABLE. A later slice wires
        // MarketDepthPort here.
        DomSignal dom = DomSignal.UNAVAILABLE;
        ReactionPattern reaction = classifyReaction(instrument, timeframe);

        return new TriggerContext(signature, buyRatio, cumulativeDelta, dom, reaction, quality);
    }

    /**
     * Back-compat shim — keeps the old 1-arg signature so tests / callers that
     * don't need reaction detection still work. The shim reports no reaction
     * (equivalent to {@link ReactionPattern#NONE}).
     */
    public TriggerContext build(IndicatorSnapshot snapshot) {
        BigDecimal buyRatio = snapshot.buyRatio();
        BigDecimal cumulativeDelta = snapshot.cumulativeDelta();
        TickDataQuality quality = inferQuality(snapshot);
        DeltaSignature signature = classify(snapshot, buyRatio, cumulativeDelta);
        return new TriggerContext(signature, buyRatio, cumulativeDelta,
            DomSignal.UNAVAILABLE, ReactionPattern.NONE, quality);
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

    private static TickDataQuality inferQuality(IndicatorSnapshot s) {
        // The snapshot doesn't carry provenance; the order-flow source is currently
        // CLV-estimated in production (tick subscription pending). Report CLV when
        // we have any delta value, UNAVAILABLE otherwise.
        return (s.buyRatio() != null || s.cumulativeDelta() != null)
            ? TickDataQuality.CLV_ESTIMATED
            : TickDataQuality.UNAVAILABLE;
    }

    private static DeltaSignature classify(IndicatorSnapshot s, BigDecimal buyRatio,
                                           BigDecimal cumulativeDelta) {
        if (buyRatio == null && cumulativeDelta == null) return DeltaSignature.NEUTRAL;
        double r = buyRatio == null ? 0.5 : buyRatio.doubleValue();
        boolean heavyBuyers = r > 0.65;
        boolean heavySellers = r < 0.35;

        // Crude heuristic — the real ABSORPTION classifier belongs to a tick-stream
        // aware detector (future slice). Here: heavy one-sided delta without a
        // corresponding EMA9 push suggests absorption.
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
