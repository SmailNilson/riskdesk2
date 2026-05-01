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
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

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

    /**
     * A {@link SetupPhase#DETECTED} row is considered "fresh" for this long
     * after creation. While fresh, repeat scans for the same instrument+
     * direction reuse the existing row instead of inserting a new one.
     * Once stale, the row is invalidated and a fresh one may be born.
     */
    static final Duration DETECTED_TTL = Duration.ofMinutes(30);

    private final SetupGateChain gateChain;
    private final RegimeSwitchPolicy regimeSwitchPolicy;
    private final SetupRepositoryPort repositoryPort;
    private final SetupNotificationPort notificationPort;
    private final IndicatorsPort indicatorsPort;
    private final StrategyPort strategyPort;

    /**
     * Per-instrument lock guarding the read-modify-write block in
     * {@link #evaluate}. Without it, two concurrent {@code onSnapshot}
     * invocations could both see "no fresh DETECTED row" and both insert,
     * producing duplicate active rows and duplicate WS notifications.
     * {@code QuantGateService} releases its own per-instrument lock before
     * invoking us, so we cannot rely on upstream serialisation.
     */
    private final Map<Instrument, ReentrantLock> instrumentLocks = new EnumMap<>(Instrument.class);

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
        double bbWidthPct    = computeBbWidthPct(indicators, snapshot.price());
        double dayMoveAbsPct = computeDayMoveAbsPct(snapshot);
        SetupStyle   style  = regimeSwitchPolicy.determineStyle(regime, bbWidthPct, dayMoveAbsPct);
        SetupTemplate template = classifyTemplate(snapshot, indicators, regime, direction);

        // Dedup + cleanup. Without this every qualifying scan would mint a
        // new DETECTED row, so a sustained signal would accumulate dozens of
        // identical rows (none of which the engine yet advances out of
        // DETECTED). Two passes:
        //   1) Invalidate DETECTED rows older than DETECTED_TTL — bounds
        //      stale-row growth even when phase advancement isn't wired up.
        //   2) If a fresh DETECTED row already covers this direction, skip
        //      the insert. The existing row remains the canonical reference.
        //
        // The whole read-modify-write block runs under a per-instrument lock
        // because QuantGateService releases its scan lock before calling us
        // — without this lock, two concurrent scans could both observe
        // "no fresh DETECTED" and both insert duplicates.
        ReentrantLock lock = lockFor(instrument);
        lock.lock();
        try {
            Instant now = Instant.now();
            List<SetupRecommendation> active = repositoryPort.findActiveByInstrument(instrument);
            for (SetupRecommendation existing : active) {
                if (existing.phase() == SetupPhase.DETECTED
                    && Duration.between(existing.detectedAt(), now).compareTo(DETECTED_TTL) > 0) {
                    repositoryPort.updatePhase(existing.id(), SetupPhase.INVALIDATED, now);
                }
            }
            boolean alreadyDetected = active.stream().anyMatch(s ->
                s.phase() == SetupPhase.DETECTED
                && s.direction() == direction
                && Duration.between(s.detectedAt(), now).compareTo(DETECTED_TTL) <= 0
            );
            if (alreadyDetected) {
                if (log.isDebugEnabled()) {
                    log.debug("setup dedup skip — fresh DETECTED already exists instrument={} direction={}",
                        instrument, direction);
                }
                return;
            }

            SetupRecommendation recommendation = buildRecommendation(
                instrument, snapshot, direction, regime, style, template, gateResults
            );

            repositoryPort.save(recommendation);
            notificationPort.publish(instrument, recommendation);

            log.info("setup detected instrument={} template={} direction={} score={}",
                instrument, template, direction, recommendation.finalScore());
        } finally {
            lock.unlock();
        }
    }

    private synchronized ReentrantLock lockFor(Instrument instrument) {
        return instrumentLocks.computeIfAbsent(instrument, k -> new ReentrantLock());
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

    /**
     * VWAP band spread as a percent of price. Returns {@link Double#NaN}
     * when the upstream indicator snapshot does not yet carry the bands
     * (cold start, no candle yet).
     */
    private double computeBbWidthPct(IndicatorsSnapshot indicators, Double price) {
        if (indicators == null || price == null || price <= 0) return Double.NaN;
        Double upper = indicators.vwapUpperBand();
        Double lower = indicators.vwapLowerBand();
        if (upper == null || lower == null) return Double.NaN;
        return Math.abs(upper - lower) / price * 100.0;
    }

    /**
     * Absolute session move as a percent of current price. {@code dayMove}
     * is in points, so dividing by price gives a percent. Returns
     * {@link Double#NaN} when price is missing.
     */
    private double computeDayMoveAbsPct(QuantSnapshot snapshot) {
        Double price = snapshot.price();
        if (price == null || price <= 0) return Double.NaN;
        return Math.abs(snapshot.dayMove()) / price * 100.0;
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
