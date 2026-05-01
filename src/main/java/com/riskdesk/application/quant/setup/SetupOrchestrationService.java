package com.riskdesk.application.quant.setup;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.pattern.OrderFlowPattern;
import com.riskdesk.domain.quant.setup.GateCheckResult;
import com.riskdesk.domain.quant.setup.SetupEvaluationContext;
import com.riskdesk.domain.quant.setup.SetupGateChain;
import com.riskdesk.domain.quant.setup.SetupPhase;
import com.riskdesk.domain.quant.setup.SetupRecommendation;
import com.riskdesk.domain.quant.setup.SetupStyle;
import com.riskdesk.domain.quant.setup.SetupTemplate;
import com.riskdesk.domain.quant.setup.port.RegimeSwitchPolicy;
import com.riskdesk.domain.quant.setup.port.SetupNotificationPort;
import com.riskdesk.domain.quant.setup.port.SetupRepositoryPort;
import com.riskdesk.domain.quant.structure.IndicatorsPort;
import com.riskdesk.domain.quant.structure.IndicatorsSnapshot;
import com.riskdesk.domain.quant.structure.StrategyPort;
import com.riskdesk.domain.quant.structure.StrategyVotes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Receives a {@link QuantSnapshot} from {@code QuantGateService} and
 * decides whether it qualifies as a scalp or day-trading setup.
 *
 * <p>Called via an {@code ObjectProvider} from {@code QuantGateService} so it
 * is completely optional — removing this bean (or toggling the feature flag)
 * has zero impact on the existing quant scan pipeline.</p>
 */
@Service
public class SetupOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(SetupOrchestrationService.class);

    private static final String ENABLED_PROP = "${riskdesk.setup.enabled:true}";

    private final SetupGateChain gateChain;
    private final RegimeSwitchPolicy regimeSwitchPolicy;
    private final SetupRepositoryPort repositoryPort;
    private final SetupNotificationPort notificationPort;
    private final IndicatorsPort indicatorsPort;
    private final StrategyPort strategyPort;

    @org.springframework.beans.factory.annotation.Value(ENABLED_PROP)
    private boolean enabled;

    public SetupOrchestrationService(
        SetupGateChain gateChain,
        RegimeSwitchPolicy regimeSwitchPolicy,
        SetupRepositoryPort repositoryPort,
        SetupNotificationPort notificationPort,
        IndicatorsPort indicatorsPort,
        StrategyPort strategyPort
    ) {
        this.gateChain         = gateChain;
        this.regimeSwitchPolicy = regimeSwitchPolicy;
        this.repositoryPort    = repositoryPort;
        this.notificationPort  = notificationPort;
        this.indicatorsPort    = indicatorsPort;
        this.strategyPort      = strategyPort;
    }

    /**
     * Entry point called by {@code QuantGateService} after every scan.
     * Silently no-ops when the feature is disabled or gates fail.
     */
    public void onSnapshot(Instrument instrument, QuantSnapshot snapshot) {
        if (!enabled) return;
        try {
            evaluate(instrument, snapshot);
        } catch (RuntimeException e) {
            log.warn("setup-orchestration failed instrument={}: {}", instrument, e.toString());
        }
    }

    private void evaluate(Instrument instrument, QuantSnapshot snapshot) {
        IndicatorsSnapshot indicators = safeIndicators(instrument);
        StrategyVotes strategyVotes   = safeStrategyVotes(instrument);

        SetupEvaluationContext ctx = new SetupEvaluationContext(
            instrument, snapshot, indicators, strategyVotes, Instant.now()
        );

        List<GateCheckResult> gateResults = gateChain.evaluateAll(ctx);
        if (!SetupGateChain.allPassed(gateResults)) {
            if (log.isDebugEnabled()) {
                long failed = gateResults.stream().filter(r -> !r.passed()).count();
                log.debug("setup gates failed instrument={} failed={}/{}", instrument, failed, gateResults.size());
            }
            return;
        }

        Direction direction = resolveDirection(snapshot);
        if (direction == null) return;

        MarketRegime regime = resolveRegime(indicators);
        SetupStyle   style  = regimeSwitchPolicy.determineStyle(regime, 50.0, 50.0);
        SetupTemplate template = classifyTemplate(snapshot, indicators, regime, direction);

        SetupRecommendation recommendation = buildRecommendation(
            instrument, snapshot, direction, regime, style, template, gateResults
        );

        repositoryPort.save(recommendation);
        notificationPort.publish(instrument, recommendation);

        log.info("setup detected instrument={} template={} direction={} score={}",
            instrument, template, direction, recommendation.finalScore());
    }

    // ── Helper methods ──────────────────────────────────────────────────────

    private Direction resolveDirection(QuantSnapshot snapshot) {
        boolean shortOk = !snapshot.shortBlocked() && snapshot.score() >= 5;
        boolean longOk  = !snapshot.longBlocked()  && snapshot.longScore() >= 5;
        if (shortOk && longOk) {
            return snapshot.score() >= snapshot.longScore() ? Direction.SHORT : Direction.LONG;
        }
        if (shortOk) return Direction.SHORT;
        if (longOk)  return Direction.LONG;
        return null;
    }

    private MarketRegime resolveRegime(IndicatorsSnapshot indicators) {
        if (indicators == null || indicators.swingBias() == null) return MarketRegime.UNKNOWN;
        return MarketRegime.fromDetectorLabel(indicators.swingBias());
    }

    private SetupTemplate classifyTemplate(QuantSnapshot snapshot,
                                           IndicatorsSnapshot indicators,
                                           MarketRegime regime,
                                           Direction direction) {
        if (indicators == null) return SetupTemplate.D_MTF_ALIGN;

        // FVG sweep takes precedence when a FVG is active near current price
        if (hasFvgNearPrice(indicators, snapshot.price())) {
            return SetupTemplate.E_FVG_SWEEP;
        }

        // VWAP mean-reversion in ranging market
        Double vwap = indicators.vwap();
        if (regime == MarketRegime.RANGING && vwap != null && snapshot.price() != null) {
            double distPct = Math.abs(snapshot.price() - vwap) / vwap * 100.0;
            if (distPct < 0.3) {
                return SetupTemplate.B_SCALP_MR;
            }
        }

        // HTF premium/discount reversal
        String zone = indicators.currentZone();
        if ("PREMIUM".equals(zone) && direction == Direction.SHORT) return SetupTemplate.A_DAY_REVERSAL;
        if ("DISCOUNT".equals(zone) && direction == Direction.LONG)  return SetupTemplate.A_DAY_REVERSAL;

        // Multi-timeframe alignment default
        return SetupTemplate.D_MTF_ALIGN;
    }

    private boolean hasFvgNearPrice(IndicatorsSnapshot indicators, Double price) {
        if (price == null) return false;
        var obs = indicators.activeOrderBlocks();
        if (obs == null || obs.isEmpty()) return false;
        return obs.stream().anyMatch(ob -> {
            if (ob.low() == null || ob.high() == null) return false;
            double mid = (ob.low() + ob.high()) / 2.0;
            return Math.abs(mid - price) / price < 0.002; // within 0.2%
        });
    }

    private SetupRecommendation buildRecommendation(Instrument instrument,
                                                     QuantSnapshot snapshot,
                                                     Direction direction,
                                                     MarketRegime regime,
                                                     SetupStyle style,
                                                     SetupTemplate template,
                                                     List<GateCheckResult> gateResults) {
        double price = snapshot.price();
        BigDecimal entry, sl, tp1, tp2;
        double rr = template.targetRr;

        if (direction == Direction.SHORT) {
            entry = bd(price);
            sl    = bd(price + QuantSnapshot.SL_OFFSET);
            tp1   = bd(price - QuantSnapshot.TP1_OFFSET);
            tp2   = bd(price - QuantSnapshot.TP2_OFFSET);
        } else {
            entry = bd(price);
            sl    = bd(price - QuantSnapshot.SL_OFFSET);
            tp1   = bd(price + QuantSnapshot.TP1_OFFSET);
            tp2   = bd(price + QuantSnapshot.TP2_OFFSET);
        }

        int score = direction == Direction.SHORT ? snapshot.finalScore() : snapshot.longFinalScore();
        Instant now = Instant.now();

        return new SetupRecommendation(
            UUID.randomUUID(),
            instrument,
            template,
            style,
            SetupPhase.DETECTED,
            regime,
            direction,
            score,
            entry,
            sl,
            tp1,
            tp2,
            rr,
            null,  // playbookId — future integration
            gateResults,
            now,
            now
        );
    }

    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private IndicatorsSnapshot safeIndicators(Instrument instrument) {
        try {
            return indicatorsPort.snapshot5m(instrument).orElse(null);
        } catch (RuntimeException e) {
            log.debug("indicators unavailable instrument={}: {}", instrument, e.toString());
            return null;
        }
    }

    private StrategyVotes safeStrategyVotes(Instrument instrument) {
        try {
            return strategyPort.votes5m(instrument).orElse(null);
        } catch (RuntimeException e) {
            log.debug("strategy votes unavailable instrument={}: {}", instrument, e.toString());
            return null;
        }
    }
}
