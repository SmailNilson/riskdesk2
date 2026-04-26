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

        var indicatorsFut = CompletableFuture.supplyAsync(
            () -> indicatorPort.indicatorsAsOf(instrument, timeframe, decisionT), executor);
        var smcFut = CompletableFuture.supplyAsync(
            () -> indicatorPort.smcAsOf(instrument, timeframe, decisionT), executor);
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
        // fixed-size analysisExecutor pool and starve subsequent captures
        // (PR #269 review fix).
        java.util.List<CompletableFuture<?>> all = java.util.List.of(
            indicatorsFut, smcFut, orderFlowFut, momentumFut,
            absorptionFut, distFut, cycleFut, macroFut);

        try {
            CompletableFuture.allOf(all.toArray(new CompletableFuture<?>[0]))
                .get(FAN_OUT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Free executor threads by cancelling outstanding work. Tasks that
            // already completed are no-ops; in-flight tasks are interrupted
            // (mayInterruptIfRunning=true) so blocking I/O can unwind.
            for (CompletableFuture<?> f : all) {
                if (!f.isDone()) f.cancel(true);
            }
            throw new StaleSnapshotException("Fan-out timeout or failure: " + e.getMessage());
        }

        Instant captureT = Instant.now();

        var indicators = indicatorsFut.join();
        var smc = smcFut.join();
        var orderFlow = orderFlowFut.join();
        var momentum = momentumFut.join();
        var absorption = absorptionFut.join();
        var dist = distFut.join();
        var cycle = cycleFut.join();
        var macro = macroFut.join();

        // Indicator + SMC staleness scales with the timeframe (HTF caches refresh slowly
        // — a 30s-old 1h indicator is still meaningful, the candle hasn't closed yet).
        Duration indicatorBudget = indicatorStalenessBudget(timeframe);
        rejectStaleness("indicators", indicators.asOf(), captureT, indicatorBudget);
        rejectStaleness("smc", smc.asOf(), captureT, indicatorBudget);
        // Order-flow + macro must be near-real-time regardless of the analysis timeframe
        // — they reflect the live tick stream, not closed bars.
        rejectStaleness("orderFlow", orderFlow.asOf(), captureT, ORDER_FLOW_MAX_STALENESS);
        rejectStaleness("macro", macro.asOf(), captureT, ORDER_FLOW_MAX_STALENESS);

        return new LiveAnalysisSnapshot(
            instrument, timeframe, decisionT, captureT,
            TriLayerScoringEngine.CURRENT_VERSION,
            indicators.indicators().lastPrice(),
            indicators.indicators(),
            smc.smc(),
            orderFlow.context(),
            momentum, absorption, dist, cycle,
            macro.macro()
        );
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
