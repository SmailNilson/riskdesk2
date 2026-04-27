package com.riskdesk.application.service.analysis;

import com.riskdesk.domain.analysis.model.LiveAnalysisSnapshot;
import com.riskdesk.domain.analysis.port.IndicatorSnapshotPort;
import com.riskdesk.domain.analysis.port.MacroContextPort;
import com.riskdesk.domain.analysis.port.OrderFlowReadPort;
import com.riskdesk.domain.analysis.service.TriLayerScoringEngine;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Timeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Captures a {@link LiveAnalysisSnapshot} by fanning out to the four read-only
 * ports in parallel, then enforces a staleness budget so we never score a
 * Frankenstein assembled from data points minutes apart.
 * <p>
 * Causality contract: {@code decisionTimestamp} is taken BEFORE the parallel
 * fan-out. Every port filters its events to that instant. The aggregator
 * rejects the snapshot if any port returned data more than {@link #MAX_STALENESS}
 * before the capture wall-clock.
 */
@Service
public class AnalysisSnapshotAggregator {

    private static final Logger log = LoggerFactory.getLogger(AnalysisSnapshotAggregator.class);
    /** Order-flow + macro must be near-real-time. Indicators allowed to scale with TF. */
    private static final Duration ORDER_FLOW_MAX_STALENESS = Duration.ofSeconds(5);
    private static final long FAN_OUT_TIMEOUT_SECONDS = 3;
    private static final int MOMENTUM_WINDOW = 15;
    private static final int ABSORPTION_WINDOW = 10;
    private static final int DIST_RECENT = 5;
    private static final int CYCLE_RECENT = 5;

    private final IndicatorSnapshotPort indicatorPort;
    private final OrderFlowReadPort orderFlowPort;
    private final MacroContextPort macroPort;
    private final ExecutorService executor;

    public AnalysisSnapshotAggregator(IndicatorSnapshotPort indicatorPort,
                                       OrderFlowReadPort orderFlowPort,
                                       MacroContextPort macroPort,
                                       @Qualifier("analysisExecutor") ExecutorService executor) {
        this.indicatorPort = indicatorPort;
        this.orderFlowPort = orderFlowPort;
        this.macroPort = macroPort;
        this.executor = executor;
    }

    public LiveAnalysisSnapshot capture(Instrument instrument, Timeframe timeframe) {
        Instant decisionT = Instant.now();

        // PR #269 round-8 review fix: indicators and SMC must come from a
        // single computation pass to preserve coherence. Previously they were
        // two parallel futures that on cache miss could observe different
        // candle states. One combined call uses the cache once and returns
        // both views with a shared asOf.
        var indicatorAndSmcFut = CompletableFuture.supplyAsync(
            () -> indicatorPort.combinedAsOf(instrument, timeframe, decisionT), executor);
        var orderFlowFut = CompletableFuture.supplyAsync(
            () -> orderFlowPort.contextAsOf(instrument, decisionT), executor);
        var momentumFut = CompletableFuture.supplyAsync(
            () -> orderFlowPort.recentMomentum(instrument, decisionT, MOMENTUM_WINDOW), executor);
        var absorptionFut = CompletableFuture.supplyAsync(
            () -> orderFlowPort.recentAbsorption(instrument, decisionT, ABSORPTION_WINDOW), executor);
        var distFut = CompletableFuture.supplyAsync(
            () -> orderFlowPort.recentDistribution(instrument, decisionT, DIST_RECENT), executor);
        var cycleFut = CompletableFuture.supplyAsync(
            () -> orderFlowPort.recentCycle(instrument, decisionT, CYCLE_RECENT), executor);
        var macroFut = CompletableFuture.supplyAsync(
            () -> macroPort.contextAsOf(decisionT), executor);

        // Keep the list around so we can cancel them all if any slow dependency
        // hangs the fan-out — without this, hung tasks keep occupying the
        // fixed-size analysisExecutor pool and starve subsequent captures.
        java.util.List<CompletableFuture<?>> all = java.util.List.of(
            indicatorAndSmcFut, orderFlowFut, momentumFut,
            absorptionFut, distFut, cycleFut, macroFut);

        try {
            CompletableFuture.allOf(all.toArray(new CompletableFuture<?>[0]))
                .get(FAN_OUT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            cancelOutstanding(all);
            // Genuine staleness — caller retries (controller maps to HTTP 503).
            throw new StaleSnapshotException(
                "Fan-out timeout after " + FAN_OUT_TIMEOUT_SECONDS + "s");
        } catch (InterruptedException e) {
            cancelOutstanding(all);
            Thread.currentThread().interrupt();
            // Treat interrupt as transient staleness — same retry semantics.
            throw new StaleSnapshotException("Fan-out interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            // PR #269 round-5 review fix: a real adapter failure (DB down,
            // NPE, IBKR client error, ...) must NOT be silenced as "stale" —
            // that masks production faults and triggers pointless retry loops
            // on broken infrastructure. Surface the original cause so the
            // controller maps it to HTTP 500 with the real stack/message.
            cancelOutstanding(all);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Fan-out adapter failure for {} {} — {}",
                instrument, timeframe, cause.toString());
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException("Fan-out adapter failure: " + cause.getMessage(), cause);
        }

        Instant captureT = Instant.now();

        var combined = indicatorAndSmcFut.join();
        var orderFlow = orderFlowFut.join();
        var momentum = momentumFut.join();
        var absorption = absorptionFut.join();
        var dist = distFut.join();
        var cycle = cycleFut.join();
        var macro = macroFut.join();

        // Indicator + SMC staleness scales with the timeframe (HTF caches refresh slowly
        // — a 30s-old 1h indicator is still meaningful, the candle hasn't closed yet).
        // Both layers share a single asOf since they came from one computation.
        Duration indicatorBudget = indicatorStalenessBudget(timeframe);
        rejectStaleness("indicators+smc", combined.asOf(), captureT, indicatorBudget);
        // Order-flow must be near-real-time regardless of the analysis timeframe —
        // it reflects the live tick stream, not closed bars.
        rejectStaleness("orderFlow", orderFlow.asOf(), captureT, ORDER_FLOW_MAX_STALENESS);

        // Macro is a soft-fail (PR #269 round-7 review): the rule-based scoring
        // engine doesn't currently consume DXY at all (the layer hooks are
        // there but no contribution is wired). When DxyMarketService falls back
        // to a persisted snapshot during a quote outage, the timestamp is old
        // and the 5s budget would reject the entire capture — turning a macro-
        // feed outage into a full /api/analysis/live 503 over scoring inputs
        // that aren't even read. Log loudly and pass null context instead.
        var effectiveMacro = checkMacroSoftFail(macro.asOf(), macro.macro(), captureT,
            instrument, timeframe);

        return new LiveAnalysisSnapshot(
            instrument, timeframe, decisionT, captureT,
            TriLayerScoringEngine.CURRENT_VERSION,
            combined.indicators().lastPrice(),
            combined.indicators(),
            combined.smc(),
            orderFlow.context(),
            momentum, absorption, dist, cycle,
            effectiveMacro
        );
    }

    /**
     * Macro staleness is non-fatal — when the DXY feed degrades to a fallback
     * snapshot, we keep the capture going with neutral macro context rather
     * than 503-ing the whole {@code /api/analysis/live} flow. Returns the
     * provided macro when fresh, a null-valued macro otherwise.
     */
    private static com.riskdesk.domain.analysis.model.MacroContext checkMacroSoftFail(
            Instant asOf, com.riskdesk.domain.analysis.model.MacroContext macro,
            Instant captureT,
            com.riskdesk.domain.model.Instrument instrument,
            com.riskdesk.domain.shared.vo.Timeframe timeframe) {
        if (asOf == null) return macro;
        Duration age = Duration.between(asOf, captureT);
        if (age.compareTo(ORDER_FLOW_MAX_STALENESS) > 0) {
            log.warn("Macro stale by {}s for {} {} — using neutral context (soft-fail)",
                age.toSeconds(), instrument, timeframe);
            return new com.riskdesk.domain.analysis.model.MacroContext(null, null, "FLAT");
        }
        return macro;
    }

    /**
     * Cancels every still-running future. Tasks that already completed are
     * no-ops; in-flight tasks get an interrupt so blocking I/O can unwind
     * (mayInterruptIfRunning=true). Without this, hung tasks keep occupying
     * the fixed-size analysisExecutor pool.
     */
    private static void cancelOutstanding(java.util.List<CompletableFuture<?>> futures) {
        for (CompletableFuture<?> f : futures) {
            if (!f.isDone()) f.cancel(true);
        }
    }

    private static void rejectStaleness(String source, Instant asOf, Instant captureT, Duration budget) {
        if (asOf == null) return;
        Duration age = Duration.between(asOf, captureT);
        if (age.compareTo(budget) > 0) {
            log.warn("Snapshot rejected — {} stale by {}s (budget {}s)",
                source, age.toSeconds(), budget.toSeconds());
            throw new StaleSnapshotException(
                source + " stale by " + age.toSeconds() + "s (budget " + budget.toSeconds() + "s)");
        }
    }

    /**
     * Indicator/SMC staleness budget scales with the analysis timeframe so that
     * HTF analyses don't reject every cached read. Rule of thumb: ½ of the
     * timeframe period, capped at 5 minutes (the daily-cache TTL).
     */
    private static Duration indicatorStalenessBudget(Timeframe tf) {
        int halfPeriodSeconds = Math.max(5, tf.periodSeconds() / 2);
        return Duration.ofSeconds(Math.min(halfPeriodSeconds, 300));
    }
}
