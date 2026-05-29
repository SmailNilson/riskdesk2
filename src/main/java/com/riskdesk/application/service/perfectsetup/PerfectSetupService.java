package com.riskdesk.application.service.perfectsetup;

import com.riskdesk.application.dto.IcebergEventView;
import com.riskdesk.application.quant.automation.QuantAutoArmService;
import com.riskdesk.application.service.FlashCrashStatusService;
import com.riskdesk.application.service.OrderFlowHistoryService;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.indicators.AtrCalculator;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.event.PerfectSetupDetected;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.orderflow.model.DistributionSignal;
import com.riskdesk.domain.orderflow.model.SmartMoneyCycleSignal;
import com.riskdesk.domain.orderflow.perfectsetup.FlashCrashContext;
import com.riskdesk.domain.orderflow.perfectsetup.IcebergContext;
import com.riskdesk.domain.orderflow.perfectsetup.PerfectSetupAxis;
import com.riskdesk.domain.orderflow.perfectsetup.PerfectSetupDetector;
import com.riskdesk.domain.orderflow.perfectsetup.PerfectSetupDirection;
import com.riskdesk.domain.orderflow.perfectsetup.PerfectSetupInputs;
import com.riskdesk.domain.orderflow.perfectsetup.PerfectSetupSignal;
import com.riskdesk.domain.orderflow.perfectsetup.PerfectSetupState;
import com.riskdesk.domain.orderflow.perfectsetup.PerfectSetupThresholds;
import com.riskdesk.domain.quant.automation.AutoArmDecision;
import com.riskdesk.domain.quant.automation.AutoArmDirection;
import com.riskdesk.domain.quant.model.LivePriceSnapshot;
import com.riskdesk.domain.quant.port.AbsorptionPort;
import com.riskdesk.domain.quant.port.CyclePort;
import com.riskdesk.domain.quant.port.DistributionPort;
import com.riskdesk.domain.quant.port.LivePricePort;
import com.riskdesk.domain.quant.structure.IndicatorsPort;
import com.riskdesk.domain.quant.structure.IndicatorsSnapshot;
import com.riskdesk.infrastructure.config.PerfectSetupProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-layer orchestrator for the Perfect Setup detector. On a fixed
 * cadence it gathers order-flow context for each configured instrument from the
 * existing ports/read-services, runs the pure {@link PerfectSetupDetector},
 * keeps the per-instrument latest signal in memory, and:
 *
 * <ul>
 *   <li>publishes a live snapshot to {@code /topic/perfect-setup} every scan;</li>
 *   <li>publishes a {@link PerfectSetupDetected} domain event on every state
 *       <em>transition</em> (consistent with the project's alert rule);</li>
 *   <li>bridges an ARM transition to {@link QuantAutoArmService} when
 *       {@code riskdesk.perfect-setup.auto-arm.enabled} is on (advisory by default).</li>
 * </ul>
 *
 * <p>No new persistence — the signal lives in memory and on the wire. The
 * auto-arm bridge reuses the existing {@code trade_executions} path.</p>
 */
@Service
public class PerfectSetupService {

    private static final Logger log = LoggerFactory.getLogger(PerfectSetupService.class);
    public static final String TOPIC = "/topic/perfect-setup";

    private final PerfectSetupProperties props;
    private final AbsorptionPort absorptionPort;
    private final DistributionPort distributionPort;
    private final CyclePort cyclePort;
    private final LivePricePort livePricePort;
    private final IndicatorsPort indicatorsPort;
    private final OrderFlowHistoryService orderFlowHistory;
    private final FlashCrashStatusService flashCrashStatus;
    private final CandleRepositoryPort candleRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectProvider<QuantAutoArmService> autoArmProvider;
    private final Clock clock;

    private final PerfectSetupDetector detector = new PerfectSetupDetector();
    private final Map<Instrument, PerfectSetupSignal> latest = new ConcurrentHashMap<>();

    public PerfectSetupService(PerfectSetupProperties props,
                               AbsorptionPort absorptionPort,
                               DistributionPort distributionPort,
                               CyclePort cyclePort,
                               LivePricePort livePricePort,
                               IndicatorsPort indicatorsPort,
                               OrderFlowHistoryService orderFlowHistory,
                               FlashCrashStatusService flashCrashStatus,
                               CandleRepositoryPort candleRepository,
                               ApplicationEventPublisher eventPublisher,
                               SimpMessagingTemplate messagingTemplate,
                               ObjectProvider<QuantAutoArmService> autoArmProvider,
                               Clock clock) {
        this.props = props;
        this.absorptionPort = absorptionPort;
        this.distributionPort = distributionPort;
        this.cyclePort = cyclePort;
        this.livePricePort = livePricePort;
        this.indicatorsPort = indicatorsPort;
        this.orderFlowHistory = orderFlowHistory;
        this.flashCrashStatus = flashCrashStatus;
        this.candleRepository = candleRepository;
        this.eventPublisher = eventPublisher;
        this.messagingTemplate = messagingTemplate;
        this.autoArmProvider = autoArmProvider;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${riskdesk.perfect-setup.eval-interval-ms:5000}", initialDelay = 100_000)
    public void evaluateAll() {
        if (!props.isEnabled()) return;
        for (Instrument instrument : configuredInstruments()) {
            try {
                evaluateInstrument(instrument, clock.instant());
            } catch (RuntimeException e) {
                log.debug("perfect-setup evaluate failed instrument={}: {}", instrument, e.toString());
            }
        }
    }

    /** Latest signal for one instrument (REST seed), or {@code null} if never evaluated. */
    public PerfectSetupSignal latest(Instrument instrument) {
        return latest.get(instrument);
    }

    /** Snapshot of the latest signals for all evaluated instruments. */
    public Collection<PerfectSetupSignal> latestAll() {
        return List.copyOf(latest.values());
    }

    /**
     * Evaluate a single instrument as of {@code now}. Package-private so tests
     * can drive a deterministic clock. Returns the new signal.
     */
    PerfectSetupSignal evaluateInstrument(Instrument instrument, Instant now) {
        PerfectSetupInputs inputs = gatherInputs(instrument, now);
        PerfectSetupSignal prior = latest.get(instrument);
        PerfectSetupThresholds thresholds = props.toThresholds();

        PerfectSetupSignal signal = detector.evaluate(inputs, prior, thresholds);
        latest.put(instrument, signal);

        PerfectSetupState priorState = prior == null ? PerfectSetupState.IDLE : prior.state();
        boolean transition = signal.state() != priorState;

        // Live panel always gets the current snapshot.
        publishSnapshot(signal);

        if (transition) {
            eventPublisher.publishEvent(new PerfectSetupDetected(instrument, signal, priorState, now));
            log.info("perfect-setup {} {} -> {} score={}/{} {}",
                instrument, priorState, signal.state(), signal.score(),
                PerfectSetupThresholds.MAX_SCORE, signal.reasoning());

            boolean newlyArmed = signal.state().isArmed() && !priorState.isArmed();
            if (newlyArmed && props.getAutoArm().isEnabled()) {
                bridgeToAutoArm(instrument, signal);
            }
        }
        return signal;
    }

    // ------------------------------------------------------------------
    // Input gathering (existing beans only)
    // ------------------------------------------------------------------

    private PerfectSetupInputs gatherInputs(Instrument instrument, Instant now) {
        Optional<LivePriceSnapshot> px = safe(() -> livePricePort.current(instrument));
        Double price = px.map(LivePriceSnapshot::price).orElse(null);
        double tickSize = instrument.getTickSize().doubleValue();
        Double atr = computeAtr(instrument);

        // Regime + cycle
        List<DistributionSignal> dists = safeList(() -> distributionPort.recent(instrument,
            now.minus(Duration.ofMinutes(props.getDistributionLookbackMinutes()))));
        DistributionSignal dist = dists.isEmpty() ? null : dists.get(0);
        String distType = dist != null ? dist.type().name() : null;
        Integer distConf = dist != null ? dist.confidenceScore() : null;

        List<SmartMoneyCycleSignal> cycles = safeList(() -> cyclePort.recent(instrument,
            now.minus(Duration.ofMinutes(props.getCycleLookbackMinutes()))));
        String cycleType = cycles.isEmpty() ? null
            : (cycles.get(0).cycleType() == null ? null : cycles.get(0).cycleType().name());

        // Absorption summary
        List<AbsorptionSignal> abs = safeList(() -> absorptionPort.recent(instrument,
            now.minus(Duration.ofMinutes(props.getAbsorptionLookbackMinutes()))));
        AbsorptionSummary absSummary = summariseAbsorption(abs);

        // Icebergs
        IcebergContext iceberg = nearestIcebergs(instrument, price, now);

        // Flash crash
        FlashCrashContext flash = flashCrashStatus.latestForInstrument(instrument)
            .map(s -> new FlashCrashContext(s.phase(), s.reversalScore()))
            .orElse(FlashCrashContext.none());

        // Indicators (VWAP / BB)
        IndicatorsSnapshot ind = safe(() -> indicatorsPort.snapshot5m(instrument)).orElse(null);
        Double vwap = ind != null ? ind.vwap() : null;
        Double vwapLower = ind != null ? ind.vwapLowerBand() : null;
        Double vwapUpper = ind != null ? ind.vwapUpperBand() : null;
        Double bbPct = ind != null ? ind.bbPct() : null;

        return new PerfectSetupInputs(instrument, price, tickSize, atr,
            distType, distConf, cycleType,
            absSummary.dominantSide(), absSummary.maxScore(), absSummary.magnitudes(),
            iceberg, flash, vwap, vwapLower, vwapUpper, bbPct, now);
    }

    private record AbsorptionSummary(String dominantSide, double maxScore, List<Long> magnitudes) {}

    private AbsorptionSummary summariseAbsorption(List<AbsorptionSignal> abs) {
        if (abs == null || abs.isEmpty()) {
            return new AbsorptionSummary(null, 0.0, List.of());
        }
        int bull = 0;
        int bear = 0;
        double max = 0.0;
        List<Long> mags = new ArrayList<>(abs.size());
        for (AbsorptionSignal a : abs) {
            if (a.side() == AbsorptionSignal.AbsorptionSide.BULLISH_ABSORPTION) bull++;
            else if (a.side() == AbsorptionSignal.AbsorptionSide.BEARISH_ABSORPTION) bear++;
            max = Math.max(max, a.absorptionScore());
            mags.add(Math.abs(a.aggressiveDelta()));
        }
        String dom = bull > bear ? "BULL" : bear > bull ? "BEAR" : "MIX";
        return new AbsorptionSummary(dom, max, mags);
    }

    private IcebergContext nearestIcebergs(Instrument instrument, Double price, Instant now) {
        List<IcebergEventView> views = safeList(() ->
            orderFlowHistory.recentIcebergs(instrument, props.getIcebergScanLimit()));
        if (views.isEmpty()) return IcebergContext.empty();

        Instant cutoff = now.minus(Duration.ofMinutes(props.getIcebergLookbackMinutes()));
        IcebergEventView bestBid = null;
        IcebergEventView bestAsk = null;
        for (IcebergEventView v : views) {
            if (v.timestamp() != null && v.timestamp().isBefore(cutoff)) continue;
            boolean isBid = v.side() != null && v.side().toUpperCase().contains("BID");
            if (isBid) {
                bestBid = closer(bestBid, v, price);
            } else {
                bestAsk = closer(bestAsk, v, price);
            }
        }
        return new IcebergContext(
            bestBid != null ? bestBid.priceLevel() : null,
            bestBid != null ? bestBid.icebergScore() : 0.0,
            bestBid != null ? bestBid.rechargeCount() : 0,
            bestAsk != null ? bestAsk.priceLevel() : null,
            bestAsk != null ? bestAsk.icebergScore() : 0.0,
            bestAsk != null ? bestAsk.rechargeCount() : 0);
    }

    /** Prefer the candidate nearer to price; with no price, keep the more recent (first-seen) one. */
    private static IcebergEventView closer(IcebergEventView current, IcebergEventView candidate, Double price) {
        if (current == null) return candidate;
        if (price == null) return current; // views are newest-first → keep first seen
        double dc = Math.abs(candidate.priceLevel() - price);
        double dd = Math.abs(current.priceLevel() - price);
        return dc < dd ? candidate : current;
    }

    private Double computeAtr(Instrument instrument) {
        try {
            List<Candle> candles = candleRepository.findRecentCandles(instrument, "5m", 20);
            BigDecimal atr = AtrCalculator.compute(candles, 14);
            return (atr != null && atr.doubleValue() > 0) ? atr.doubleValue() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Auto-arm bridge
    // ------------------------------------------------------------------

    private void bridgeToAutoArm(Instrument instrument, PerfectSetupSignal signal) {
        QuantAutoArmService autoArm = autoArmProvider.getIfAvailable();
        if (autoArm == null) return;
        if (signal.stop() == null || signal.tp1() == null) return;

        Double entry = signal.triggerLevel() != null ? signal.triggerLevel()
            : (signal.entryLow() != null && signal.entryHigh() != null
                ? (signal.entryLow() + signal.entryHigh()) / 2.0 : null);
        if (entry == null) return;

        AutoArmDirection dir = signal.direction() == PerfectSetupDirection.SHORT
            ? AutoArmDirection.SHORT : AutoArmDirection.LONG;
        double size = Math.min(1.0, Math.max(0.0001, props.getAutoArm().getSizePercent()));
        Instant decisionAt = signal.timestamp();
        Instant expiresAt = decisionAt.plusSeconds(Math.max(1, props.getArmTtlSeconds()));

        try {
            AutoArmDecision decision = new AutoArmDecision(
                dir,
                bd(entry), bd(signal.stop()), bd(signal.tp1()),
                signal.tp2() == null ? null : bd(signal.tp2()),
                size, decisionAt, expiresAt, signal.reasoning());
            autoArm.armFromPerfectSetup(instrument, decision)
                .ifPresent(exec -> log.info("perfect-setup bridged auto-arm instrument={} executionId={}",
                    instrument, exec.getId()));
        } catch (RuntimeException e) {
            log.warn("perfect-setup auto-arm bridge failed instrument={}: {}", instrument, e.toString());
        }
    }

    // ------------------------------------------------------------------
    // WebSocket
    // ------------------------------------------------------------------

    private void publishSnapshot(PerfectSetupSignal s) {
        try {
            messagingTemplate.convertAndSend(TOPIC, toPayload(s));
        } catch (RuntimeException e) {
            log.debug("perfect-setup publish failed instrument={}: {}", s.instrument(), e.toString());
        }
    }

    /** Stable JSON shape — keep field names in sync with the frontend type. */
    public static Map<String, Object> toPayload(PerfectSetupSignal s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instrument", s.instrument().name());
        m.put("direction", s.direction().name());
        m.put("state", s.state().name());
        m.put("score", s.score());
        m.put("maxScore", s.maxScore());
        List<Map<String, Object>> axes = new ArrayList<>();
        for (PerfectSetupAxis.Result r : s.axes()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("axis", r.axis().name());
            a.put("label", r.axis().label());
            a.put("passed", r.passed());
            a.put("detail", r.detail());
            axes.add(a);
        }
        m.put("axes", axes);
        m.put("entryLow", s.entryLow());
        m.put("entryHigh", s.entryHigh());
        m.put("stop", s.stop());
        m.put("tp1", s.tp1());
        m.put("tp2", s.tp2());
        m.put("riskReward", s.riskReward());
        m.put("triggerLevel", s.triggerLevel());
        m.put("invalidationLevel", s.invalidationLevel());
        m.put("reasoning", s.reasoning());
        m.put("timestamp", s.timestamp().toString());
        return m;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<Instrument> configuredInstruments() {
        List<Instrument> out = new ArrayList<>();
        for (String name : props.getInstruments()) {
            try {
                out.add(Instrument.valueOf(name.trim()));
            } catch (IllegalArgumentException ignored) {
                // unknown instrument name — skip
            }
        }
        return out;
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    private static <T> Optional<T> safe(java.util.function.Supplier<Optional<T>> supplier) {
        try {
            Optional<T> o = supplier.get();
            return o == null ? Optional.empty() : o;
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static <T> List<T> safeList(java.util.function.Supplier<List<T>> supplier) {
        try {
            List<T> l = supplier.get();
            return l == null ? List.of() : l;
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
