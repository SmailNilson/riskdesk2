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

    /** Tracks per-instrument the highest score we have already auto-advised on, so we only fire once per session. */
    private final java.util.Map<Instrument, Integer> autoAdviceFiredFor = new java.util.EnumMap<>(Instrument.class);

    /**
     * Per-instrument lock guarding the entire {@code load → evaluate → save →
     * history → publish → narration → auto-advise check} sequence. Two
     * separate guarantees:
     *
     * <ol>
     *   <li><b>State integrity.</b> Two concurrent scans on the same instrument
     *       could both load the same prior state, both append history entries
     *       on top of it, and the later save would silently drop the earlier
     *       append.</li>
     *   <li><b>Publish ordering.</b> If the lock only covered the state
     *       mutation, scan B could publish snapshot B and then scan A's publish
     *       (older) would land later — the frontend, which overwrites
     *       per-instrument state on receipt, would regress to A and could
     *       re-fire its 6/7 / 7/7 alert.</li>
     * </ol>
     *
     * Holding the lock across publish + narration is cheap because
     * {@code SimpMessagingTemplate.convertAndSend} is non-blocking. The
     * advisor LLM call is intentionally outside the lock — it can be slow,
     * and its own per-instrument cache + auto-advise gate provide the
     * deduplication. Locks across instruments are independent so MNQ / MGC /
     * MCL still scan in parallel.
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
                            GateEvaluator evaluator) {
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
    }

    /**
     * Runs a single evaluation tick for the instrument. Fetches data in parallel,
     * evaluates the gates, persists the new state and notifies subscribers.
     */
    public QuantSnapshot scan(Instrument instrument) {
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

        // Serialise the full read-evaluate-write-publish window per instrument
        // so concurrent scans (scheduler tick + manual refresh) cannot
        // (a) lose history appends, or
        // (b) publish snapshots out of order on /topic/quant/* and regress the
        //     frontend or re-emit stale 6/7 / 7/7 alerts.
        // See instrumentLocks Javadoc for full rationale.
        QuantSnapshot result;
        QuantSetupNarrationService.NarrationResult narration;
        boolean shouldAdvise;
        ReentrantLock lock = lockFor(instrument);
        lock.lock();
        try {
            QuantState saved = statePort.load(instrument);
            GateEvaluator.Outcome outcome = evaluator.evaluate(snap, saved, instrument);
            statePort.save(instrument, outcome.nextState());
            result = outcome.snapshot();

            historyStore.add(instrument, result);
            narration = narrationService.buildNarration(instrument, result, outcome.nextState(), snap);
            sessionMemoryService.recordScan(instrument,
                narration.pattern() == null ? null : narration.pattern().type());

            publish(instrument, result);
            notificationPort.publishNarration(instrument, result, narration.pattern(), narration.markdown());

            // Decide-and-mark must be inside the lock so two concurrent scans
            // can't both pass the auto-advise gate for the same score.
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
     * {@code null} if the scheduler has not produced one yet. Pure read — does
     * not run a scan, does not write to {@link QuantStatePort}, does not
     * publish to any topic. Used by {@code GET /api/quant/snapshot/{instr}} so
     * that opening the dashboard never side-effects every connected user.
     */
    public QuantSnapshot latestSnapshot(Instrument instrument) {
        return historyStore.recent(instrument, Duration.ofMinutes(5))
            .stream()
            .reduce((a, b) -> b)
            .orElse(null);
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

    private void publish(Instrument instrument, QuantSnapshot snapshot) {
        notificationPort.publishSnapshot(instrument, snapshot);
        if (snapshot.isShortSetup7_7()) {
            notificationPort.publishShortSignal7_7(instrument, snapshot);
        } else if (snapshot.isShortAlert6_7()) {
            notificationPort.publishSetupAlert6_7(instrument, snapshot);
        }
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
