package com.riskdesk.application.quant.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.orderflow.model.DistributionSignal;
import com.riskdesk.domain.orderflow.model.SmartMoneyCycleSignal;
import com.riskdesk.domain.quant.engine.GateEvaluator;
import com.riskdesk.domain.quant.model.DeltaSnapshot;
import com.riskdesk.domain.quant.model.LivePriceSnapshot;
import com.riskdesk.domain.quant.model.MarketSnapshot;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.model.QuantState;
import com.riskdesk.domain.quant.port.AbsorptionPort;
import com.riskdesk.domain.quant.port.CyclePort;
import com.riskdesk.domain.quant.port.DeltaPort;
import com.riskdesk.domain.quant.port.DistributionPort;
import com.riskdesk.domain.quant.port.LivePricePort;
import com.riskdesk.domain.quant.port.QuantNotificationPort;
import com.riskdesk.domain.quant.port.QuantStatePort;
import com.riskdesk.domain.quant.structure.IndicatorsPort;
import com.riskdesk.domain.quant.structure.IndicatorsSnapshot;
import com.riskdesk.domain.quant.structure.StrategyPort;
import com.riskdesk.domain.quant.structure.StrategyVotes;
import com.riskdesk.domain.quant.structure.StructuralFilterEvaluator;
import com.riskdesk.domain.quant.structure.StructuralFilterResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Orchestrates one Quant 7-gate evaluation cycle: parallel data fetch from
 * the 5 input ports, snapshot construction, pure {@link GateEvaluator}
 * invocation, state persistence, and outbound notification.
 *
 * <p>Aggregation windows mirror the Python reference: 3 min for absorption
 * events, 10 min for distribution / cycle events.</p>
 */
@Service
public class QuantGateService {

    private static final Logger log = LoggerFactory.getLogger(QuantGateService.class);

    private static final Duration ABS_WINDOW  = Duration.ofMinutes(3);
    private static final Duration DIST_WINDOW = Duration.ofMinutes(10);
    private static final Duration CYC_WINDOW  = Duration.ofMinutes(10);
    private static final int      ABS_SCORE_8 = 8;

    private final AbsorptionPort absorptionPort;
    private final DistributionPort distributionPort;
    private final CyclePort cyclePort;
    private final DeltaPort deltaPort;
    private final LivePricePort livePricePort;
    private final QuantStatePort statePort;
    private final QuantNotificationPort notificationPort;
    private final QuantSnapshotHistoryStore historyStore;
    private final QuantSetupNarrationService narrationService;
    private final QuantSessionMemoryService sessionMemoryService;
    private final QuantAiAdvisorService advisorService;
    private final GateEvaluator evaluator;
    private final IndicatorsPort indicatorsPort;
    private final StrategyPort strategyPort;
    private final StructuralFilterEvaluator structuralEvaluator;

    /** Tracks per-instrument the highest score we have already auto-advised on, so we only fire once per session. */
    private final java.util.Map<Instrument, Integer> autoAdviceFiredFor = new java.util.EnumMap<>(Instrument.class);

    /** Score threshold below which {@code lastSignaledScore} resets so the next rise re-fires. */
    private static final int SIGNAL_RESET_BELOW = 6;

    /**
     * Per-instrument lock guarding the entire scan pipeline — input fetch,
     * snapshot construction, state mutation, publish, narration and the
     * auto-advise gate. Three guarantees:
     *
     * <ol>
     *   <li><b>State integrity.</b> Two concurrent scans on the same instrument
     *       could both load the same prior state, both append history entries
     *       on top of it, and the later save would silently drop the earlier
     *       append.</li>
     *   <li><b>Publish ordering matches state ordering.</b> Without the publish
     *       inside the lock, scan B could publish snapshot B and then scan A's
     *       publish (older state) would land later — the frontend regresses.</li>
     *   <li><b>Capture-time ordering matches publish-time ordering.</b> If the
     *       lock only covered the state mutation, a scan whose ports replied
     *       slowly could finish capturing inputs at T1, wait for the lock,
     *       and publish AFTER another scan that captured at T2 &gt; T1.
     *       Holding the lock across the parallel port fetches makes capture
     *       and publish strictly sequential per instrument.</li>
     * </ol>
     *
     * The advisor LLM call is intentionally outside the lock — it can be slow,
     * and its own per-instrument 30 s cache provides the deduplication.
     * Different instruments still scan in parallel because each has its own
     * lock.
     */
    private final Map<Instrument, ReentrantLock> instrumentLocks = new EnumMap<>(Instrument.class);

    public QuantGateService(AbsorptionPort absorptionPort,
                            DistributionPort distributionPort,
                            CyclePort cyclePort,
                            DeltaPort deltaPort,
                            LivePricePort livePricePort,
                            QuantStatePort statePort,
                            QuantNotificationPort notificationPort,
                            QuantSnapshotHistoryStore historyStore,
                            QuantSetupNarrationService narrationService,
                            QuantSessionMemoryService sessionMemoryService,
                            QuantAiAdvisorService advisorService,
                            GateEvaluator evaluator,
                            IndicatorsPort indicatorsPort,
                            StrategyPort strategyPort,
                            StructuralFilterEvaluator structuralEvaluator) {
        this.absorptionPort = absorptionPort;
        this.distributionPort = distributionPort;
        this.cyclePort = cyclePort;
        this.deltaPort = deltaPort;
        this.livePricePort = livePricePort;
        this.statePort = statePort;
        this.notificationPort = notificationPort;
        this.historyStore = historyStore;
        this.narrationService = narrationService;
        this.sessionMemoryService = sessionMemoryService;
        this.advisorService = advisorService;
        this.evaluator = evaluator;
        this.indicatorsPort = indicatorsPort;
        this.strategyPort = strategyPort;
        this.structuralEvaluator = structuralEvaluator;
    }

    /**
     * Runs a single evaluation tick for the instrument. Holds the per-instrument
     * lock across the full pipeline (input fetch → evaluate → save → publish →
     * narration → auto-advise gate) so the chronological order of input
     * capture matches the order of downstream publish — see
     * {@link #instrumentLocks} Javadoc for the three concurrency guarantees.
     */
    public QuantSnapshot scan(Instrument instrument) {
        QuantSnapshot result;
        QuantSetupNarrationService.NarrationResult narration;
        boolean shouldAdvise;
        ReentrantLock lock = lockFor(instrument);
        lock.lock();
        try {
            Instant now = Instant.now();
            Instant absSince  = now.minus(ABS_WINDOW);
            Instant distSince = now.minus(DIST_WINDOW);
            Instant cycSince  = now.minus(CYC_WINDOW);

            CompletableFuture<List<AbsorptionSignal>>      absF  =
                CompletableFuture.supplyAsync(() -> safeList(() -> absorptionPort.recent(instrument, absSince)));
            CompletableFuture<List<DistributionSignal>>    distF =
                CompletableFuture.supplyAsync(() -> safeList(() -> distributionPort.recent(instrument, distSince)));
            CompletableFuture<List<SmartMoneyCycleSignal>> cycF  =
                CompletableFuture.supplyAsync(() -> safeList(() -> cyclePort.recent(instrument, cycSince)));
            CompletableFuture<Optional<DeltaSnapshot>>     dltF  =
                CompletableFuture.supplyAsync(() -> safeOpt(() -> deltaPort.current(instrument)));
            CompletableFuture<Optional<LivePriceSnapshot>> pxF   =
                CompletableFuture.supplyAsync(() -> safeOpt(() -> livePricePort.current(instrument)));

            CompletableFuture.allOf(absF, distF, cycF, dltF, pxF).join();

            MarketSnapshot snap = buildSnapshot(now, absF.join(), distF.join(), cycF.join(), dltF.join(), pxF.join());

            QuantState saved = statePort.load(instrument);
            GateEvaluator.Outcome outcome = evaluator.evaluate(snap, saved, instrument);
            QuantSnapshot rawSnapshot = outcome.snapshot();

            // Structural filters (PR #299): leverage the existing IndicatorService
            // and StrategyEngineService to veto SHORT setups that the pure 7-gate
            // quant score would otherwise greenlight (active bull OB, CHOPPY
            // regime, MTF bull alignment, very-bull CMF, Java NO_TRADE).
            // Narration runs first to compute the order-flow pattern, then we
            // re-render with the structural data attached so the markdown shows
            // the blocks/warnings section.
            QuantSetupNarrationService.NarrationResult preNarration =
                narrationService.buildNarration(instrument, rawSnapshot, outcome.nextState(), snap);
            StructuralFilterResult structural = evaluateStructural(
                instrument, rawSnapshot.price(), preNarration.pattern());
            result = rawSnapshot.withStructuralResult(structural);

            historyStore.add(instrument, result);
            narration = narrationService.buildNarration(instrument, result, outcome.nextState(), snap);
            sessionMemoryService.recordScan(instrument,
                narration.pattern() == null ? null : narration.pattern().type());

            // Compute the alert transition using the persisted prev value, not
            // an in-memory map — survives process restarts (PR #297 follow-up).
            int prevSignaled = outcome.nextState().lastSignaledScore();
            int newSignaled = nextSignaledScoreFor(prevSignaled, result.score());

            // Save state ONCE with the updated lastSignaledScore. Saving before
            // the publish() emit gives at-most-once semantics on the alert: a
            // crash between save and publish loses the alert (acceptable) but
            // never duplicates it on restart (the painful case).
            statePort.save(instrument, outcome.nextState().withLastSignaledScore(newSignaled));

            publish(instrument, result, prevSignaled);
            notificationPort.publishNarration(instrument, result, narration.pattern(), narration.markdown());

            // Decide-and-mark inside the lock so two concurrent scans can't
            // both pass the auto-advise gate for the same score.
            shouldAdvise = shouldAutoAdvise(instrument, result);
        } finally {
            lock.unlock();
        }

        // The advisor LLM call is slow — done outside the lock. The advisor
        // service has its own per-instrument 30 s cache so a second concurrent
        // call would just return the cached verdict.
        if (shouldAdvise) {
            com.riskdesk.domain.quant.advisor.AiAdvice advice =
                advisorService.requestAdviceIfQualified(instrument, result, narration.pattern());
            if (advice != null
                && advice.verdict() != com.riskdesk.domain.quant.advisor.AiAdvice.Verdict.UNAVAILABLE) {
                notificationPort.publishAdvice(instrument, result, advice);
            }
        }

        logScan(instrument, result);
        return result;
    }

    /**
     * Returns the most recently published snapshot for the instrument, or
     * {@code null} if no scan has ever produced one. Pure read — does not run
     * a scan, does not write to {@link QuantStatePort}, does not publish to
     * any topic.
     *
     * <p>Returns the latest entry regardless of age — a scheduler stall must
     * not erase the trader's last known view.</p>
     */
    public QuantSnapshot latestSnapshot(Instrument instrument) {
        return historyStore.latest(instrument).orElse(null);
    }

    /**
     * Runs the structural filters for the instrument. Both ports degrade
     * silently to {@link Optional#empty()} on failure; the evaluator handles
     * missing data so we never let a structural-side glitch bring down the
     * whole quant scan.
     */
    private StructuralFilterResult evaluateStructural(Instrument instrument,
                                                       Double price,
                                                       com.riskdesk.domain.quant.pattern.PatternAnalysis pattern) {
        try {
            IndicatorsSnapshot ind = safeOpt(() -> indicatorsPort.snapshot5m(instrument)).orElse(null);
            StrategyVotes strat   = safeOpt(() -> strategyPort.votes5m(instrument)).orElse(null);
            return structuralEvaluator.evaluateForShort(price, ind, strat, pattern);
        } catch (RuntimeException e) {
            log.warn("structural filter failed instrument={}: {}", instrument, e.toString());
            return StructuralFilterResult.empty();
        }
    }

    private boolean shouldAutoAdvise(Instrument instrument, QuantSnapshot snapshot) {
        if (snapshot.score() < advisorService.getTriggerScore()) return false;
        Integer alreadyFired = autoAdviceFiredFor.get(instrument);
        if (alreadyFired != null && alreadyFired >= snapshot.score()) return false;
        autoAdviceFiredFor.put(instrument, snapshot.score());
        return true;
    }

    private synchronized ReentrantLock lockFor(Instrument instrument) {
        return instrumentLocks.computeIfAbsent(instrument, k -> new ReentrantLock());
    }

    /** Exposed for the on-demand "Ask AI" flow on the controller. */
    public com.riskdesk.domain.quant.advisor.AiAdvice requestAdviceNow(Instrument instrument) {
        QuantSnapshot snapshot = historyStore.recent(instrument, java.time.Duration.ofMinutes(2))
            .stream().reduce((a, b) -> b)
            .orElse(null);
        if (snapshot == null) {
            return com.riskdesk.domain.quant.advisor.AiAdvice.unavailable("no recent snapshot — run /snapshot first");
        }
        com.riskdesk.domain.quant.pattern.PatternAnalysis pattern = null;
        com.riskdesk.domain.quant.advisor.AiAdvice advice = advisorService.requestAdvice(instrument, snapshot, pattern);
        if (advice != null
            && advice.verdict() != com.riskdesk.domain.quant.advisor.AiAdvice.Verdict.UNAVAILABLE) {
            notificationPort.publishAdvice(instrument, snapshot, advice);
        }
        return advice;
    }

    private MarketSnapshot buildSnapshot(Instant now,
                                         List<AbsorptionSignal> absorptions,
                                         List<DistributionSignal> distributions,
                                         List<SmartMoneyCycleSignal> cycles,
                                         Optional<DeltaSnapshot> delta,
                                         Optional<LivePriceSnapshot> price) {
        int absFresh = absorptions.size();
        int bull8 = (int) absorptions.stream()
            .filter(a -> a.absorptionScore() >= ABS_SCORE_8
                && a.side() == AbsorptionSignal.AbsorptionSide.BULLISH_ABSORPTION)
            .count();
        int bear8 = (int) absorptions.stream()
            .filter(a -> a.absorptionScore() >= ABS_SCORE_8
                && a.side() == AbsorptionSignal.AbsorptionSide.BEARISH_ABSORPTION)
            .count();
        double maxScore = absorptions.stream()
            .mapToDouble(AbsorptionSignal::absorptionScore)
            .max().orElse(0.0);

        DistributionSignal dist = distributions.isEmpty() ? null : distributions.get(0);
        SmartMoneyCycleSignal cyc = cycles.isEmpty() ? null : cycles.get(0);
        Integer cycleAge = (cyc != null && cyc.startedAt() != null)
            ? (int) Math.max(0, Duration.between(cyc.startedAt(), now).toMinutes())
            : null;

        return new MarketSnapshot.Builder()
            .now(now)
            .price(price.map(p -> (Double) p.price()).orElse(null))
            .priceSource(price.map(LivePriceSnapshot::source).orElse(""))
            .delta(delta.map(d -> (Double) d.delta()).orElse(null))
            .buyPct(delta.map(DeltaSnapshot::buyRatioPct).orElse(null))
            .absFresh(absFresh)
            .absBull8(bull8)
            .absBear8(bear8)
            .absMaxScore(maxScore)
            .dist(dist != null ? dist.type().name() : null,
                  dist != null ? dist.confidenceScore() : null)
            .distTimestamp(dist != null ? dist.timestamp() : null)
            .cycle(cyc != null ? cyc.cycleType().name() : null,
                   cyc != null && cyc.currentPhase() != null ? cyc.currentPhase().name() : null)
            .cycleAge(cycleAge)
            .build();
    }

    /**
     * Publishes the per-scan snapshot every time, but emits the 6/7 / 7/7
     * one-shot alerts only on TRANSITIONS into those states. This matches the
     * project's existing transition-based alert rule (CLAUDE.md /
     * ARCHITECTURE_PRINCIPLES.md "Alert Evaluation Rule") and the frontend
     * contract that {@code /topic/quant/signals} is a one-shot confirmation.
     *
     * <p>{@code prevSignaledScore} is the publisher's transition tracker: the
     * highest score for which an alert was already emitted. Caller is
     * responsible for sourcing this value from {@link QuantState} (so it
     * survives restarts) and persisting the {@link #nextSignaledScoreFor
     * computed next value}.</p>
     *
     * <p>Package-private for {@code QuantGateServiceAlertTransitionTest}.</p>
     */
    void publish(Instrument instrument, QuantSnapshot snapshot, int prevSignaledScore) {
        notificationPort.publishSnapshot(instrument, snapshot);

        int score = snapshot.score();
        if (score >= 7 && prevSignaledScore < 7) {
            notificationPort.publishShortSignal7_7(instrument, snapshot);
        } else if (score == 6 && prevSignaledScore < 6) {
            notificationPort.publishSetupAlert6_7(instrument, snapshot);
        }
    }

    /**
     * Pure transition-state machine for the alert publisher.
     *
     * <ul>
     *   <li>score ≥ 7 from anything below → return 7 (fire 7/7)</li>
     *   <li>score = 6 from below 6 → return 6 (fire 6/7)</li>
     *   <li>score &lt; 6 → return 0 (reset; a re-entry will fire again)</li>
     *   <li>otherwise → return prev unchanged (persistence at 6 or 7, no fire)</li>
     * </ul>
     */
    static int nextSignaledScoreFor(int prevSignaledScore, int currentScore) {
        if (currentScore >= 7 && prevSignaledScore < 7) return 7;
        if (currentScore == 6 && prevSignaledScore < 6) return 6;
        if (currentScore < SIGNAL_RESET_BELOW) return 0;
        return prevSignaledScore;
    }

    private void logScan(Instrument instrument, QuantSnapshot snapshot) {
        if (!log.isInfoEnabled()) return;
        String missing = snapshot.gates().entrySet().stream()
            .filter(e -> !e.getValue().ok())
            .map(e -> e.getKey().name())
            .collect(Collectors.joining(","));
        log.info("quant scan instrument={} score={}/7 missing=[{}] price={} source={}",
            instrument, snapshot.score(), missing, snapshot.price(), snapshot.priceSource());
    }

    private static <T> List<T> safeList(java.util.function.Supplier<List<T>> supplier) {
        try {
            List<T> list = supplier.get();
            return list != null ? list : List.of();
        } catch (RuntimeException e) {
            log.warn("quant port fetch failed: {}", e.toString());
            return List.of();
        }
    }

    private static <T> Optional<T> safeOpt(java.util.function.Supplier<Optional<T>> supplier) {
        try {
            Optional<T> opt = supplier.get();
            return opt != null ? opt : Optional.empty();
        } catch (RuntimeException e) {
            log.warn("quant port fetch failed: {}", e.toString());
            return Optional.empty();
        }
    }

}
