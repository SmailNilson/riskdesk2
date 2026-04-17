package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.engine.strategy.model.DeltaSignature;
import com.riskdesk.domain.engine.strategy.model.DomSignal;
import com.riskdesk.domain.engine.strategy.model.ReactionPattern;
import com.riskdesk.domain.engine.strategy.model.TickDataQuality;
import com.riskdesk.domain.engine.strategy.model.TriggerContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Produces a {@link TriggerContext} from the indicator snapshot — the cheap path.
 * A future iteration will read from the tick stream directly (see
 * {@code TickDataPort}); for now we approximate using CLV-based delta already in
 * the snapshot. Quality is reported accurately so downstream agents know to
 * discount.
 */
@Component
public class TriggerContextBuilder {

    /** |cumulativeDelta| above this fraction of volume is "heavy" — tunable. */
    private static final double HEAVY_DELTA_RATIO = 0.3;

    public TriggerContext build(IndicatorSnapshot snapshot) {
        BigDecimal buyRatio = snapshot.buyRatio();
        BigDecimal cumulativeDelta = snapshot.cumulativeDelta();
        TickDataQuality quality = inferQuality(snapshot);

        DeltaSignature signature = classify(snapshot, buyRatio, cumulativeDelta);
        // DOM is not yet on the snapshot — report UNAVAILABLE. A later slice wires
        // MarketDepthPort here.
        DomSignal dom = DomSignal.UNAVAILABLE;
        // Reaction pattern detection requires recent candles; leave NONE until a
        // dedicated detector is wired up.
        ReactionPattern reaction = ReactionPattern.NONE;

        return new TriggerContext(signature, buyRatio, cumulativeDelta, dom, reaction, quality);
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
