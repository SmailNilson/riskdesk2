package com.riskdesk.application.service.analysis.adapter;

import com.riskdesk.application.dto.AbsorptionEventView;
import com.riskdesk.application.dto.CycleEventView;
import com.riskdesk.application.dto.DistributionEventView;
import com.riskdesk.application.dto.MomentumEventView;
import com.riskdesk.application.service.OrderFlowHistoryService;
import com.riskdesk.application.service.analysis.StaleSnapshotException;
import com.riskdesk.domain.analysis.model.OrderFlowContext;
import com.riskdesk.domain.analysis.model.OrderFlowEventSummary;
import com.riskdesk.domain.analysis.port.OrderFlowReadPort;
import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.DepthMetrics;
import com.riskdesk.domain.orderflow.port.MarketDepthPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Bridges live order-flow runtime state ({@code TickDataPort}, {@code MarketDepthPort})
 * and persisted detector events ({@code OrderFlowHistoryService}) to the
 * domain-side {@link OrderFlowReadPort}.
 * <p>
 * <b>Look-ahead protection (PR #269 review fixes):</b>
 * <ol>
 *   <li>{@code contextAsOf} rejects with {@link StaleSnapshotException} when the
 *       live aggregation's {@code windowEnd} is meaningfully <i>after</i>
 *       {@code decisionAt} — that means ticks arrived between the aggregator
 *       choosing {@code decisionAt} and our async fan-out reading the latest
 *       state, so they would leak into the scored snapshot.</li>
 *   <li>{@code recent*} methods over-fetch and trim AFTER filtering to
 *       {@code decisionAt}. The previous "fetch limit then filter" order
 *       silently dropped valid pre-decision events whenever ≥1 newer event
 *       existed, biasing momentum/absorption windows toward neutral.</li>
 * </ol>
 */
@Component
public class OrderFlowReadAdapter implements OrderFlowReadPort {

    /**
     * Tolerance for "windowEnd is after decisionAt": we accept up to this much
     * future-leak because the aggregation timestamp is approximate (TickAggregation
     * windowEnd is rounded to the latest tick processed). Beyond this we reject —
     * the assumption is that a live aggregation seeded from continuous ticks
     * cannot drift more than a few hundred ms above {@code decisionAt} unless
     * fan-out latency is itself anomalous.
     */
    private static final Duration LOOK_AHEAD_TOLERANCE = Duration.ofMillis(500);

    /**
     * Per-event-type over-fetch multiplier: history is fetched at this multiple
     * of the requested limit so that pre-decision events beyond the first page
     * can be picked up after filtering. Capped at {@link #OVER_FETCH_MAX}.
     */
    private static final int OVER_FETCH_MULTIPLIER = 5;
    private static final int OVER_FETCH_MAX = 200;

    private final ObjectProvider<TickDataPort> tickDataPortProvider;
    private final ObjectProvider<MarketDepthPort> depthPortProvider;
    private final OrderFlowHistoryService historyService;

    public OrderFlowReadAdapter(ObjectProvider<TickDataPort> tickDataPortProvider,
                                 ObjectProvider<MarketDepthPort> depthPortProvider,
                                 OrderFlowHistoryService historyService) {
        this.tickDataPortProvider = tickDataPortProvider;
        this.depthPortProvider = depthPortProvider;
        this.historyService = historyService;
    }

    @Override
    public TimedOrderFlow contextAsOf(Instrument instrument, Instant decisionAt) {
        TickDataPort tickPort = tickDataPortProvider.getIfAvailable();
        MarketDepthPort depthPort = depthPortProvider.getIfAvailable();

        Optional<TickAggregation> aggOpt = tickPort != null
            ? tickPort.currentAggregation(instrument)
            : Optional.empty();
        Optional<DepthMetrics> depthOpt = depthPort != null
            ? depthPort.currentDepth(instrument)
            : Optional.empty();

        // PR #269 review fix — reject when live aggregation already includes ticks
        // that arrived after decisionAt. Honoring the no-look-ahead contract is
        // more important than producing a verdict; the caller (aggregator) maps
        // StaleSnapshotException to a 503 and the scheduler retries silently.
        if (aggOpt.isPresent() && aggOpt.get().windowEnd() != null) {
            Instant windowEnd = aggOpt.get().windowEnd();
            if (windowEnd.isAfter(decisionAt.plus(LOOK_AHEAD_TOLERANCE))) {
                throw new StaleSnapshotException(
                    "Order-flow windowEnd " + windowEnd + " is "
                    + Duration.between(decisionAt, windowEnd).toMillis()
                    + "ms after decisionAt " + decisionAt + " — would leak look-ahead ticks");
            }
        }
        if (depthOpt.isPresent() && depthOpt.get().timestamp() != null) {
            Instant depthTs = depthOpt.get().timestamp();
            if (depthTs.isAfter(decisionAt.plus(LOOK_AHEAD_TOLERANCE))) {
                throw new StaleSnapshotException(
                    "Depth timestamp " + depthTs + " is after decisionAt " + decisionAt);
            }
        }

        long delta = aggOpt.map(TickAggregation::delta).orElse(0L);
        double buyRatio = aggOpt.map(TickAggregation::buyRatioPct).orElse(50.0);
        String trend = aggOpt.map(TickAggregation::deltaTrend).orElse("FLAT");
        boolean div = aggOpt.map(TickAggregation::divergenceDetected).orElse(false);
        String divType = aggOpt.map(TickAggregation::divergenceType).orElse(null);
        String source = aggOpt.map(TickAggregation::source).orElse("UNAVAILABLE");

        Long bidSize = depthOpt.map(DepthMetrics::totalBidSize).orElse(null);
        Long askSize = depthOpt.map(DepthMetrics::totalAskSize).orElse(null);
        Double imbalance = depthOpt.map(DepthMetrics::depthImbalance).orElse(null);
        Double bestBid = depthOpt.map(DepthMetrics::bestBid).orElse(null);
        Double bestAsk = depthOpt.map(DepthMetrics::bestAsk).orElse(null);
        Double spread = depthOpt.map(DepthMetrics::spread).orElse(null);

        OrderFlowContext ctx = new OrderFlowContext(delta, buyRatio, trend, div, divType,
            source, bidSize, askSize, imbalance, bestBid, bestAsk, spread);

        Instant asOf = aggOpt.map(TickAggregation::windowEnd).orElse(Instant.now());
        return new TimedOrderFlow(ctx, asOf);
    }

    @Override
    public List<OrderFlowEventSummary> recentMomentum(Instrument instrument, Instant decisionAt, int limit) {
        return overFetchAndFilter(
            l -> historyService.recentMomentumBursts(instrument, l),
            decisionAt, limit,
            (MomentumEventView v) -> new OrderFlowEventSummary(
                v.timestamp(), "MOMENTUM", v.side(), v.momentumScore(), v.aggressiveDelta()));
    }

    @Override
    public List<OrderFlowEventSummary> recentAbsorption(Instrument instrument, Instant decisionAt, int limit) {
        return overFetchAndFilter(
            l -> historyService.recentAbsorptions(instrument, l),
            decisionAt, limit,
            (AbsorptionEventView v) -> new OrderFlowEventSummary(
                v.timestamp(), "ABSORPTION", v.side(), v.absorptionScore(), v.aggressiveDelta()));
    }

    @Override
    public List<OrderFlowEventSummary> recentDistribution(Instrument instrument, Instant decisionAt, int limit) {
        return overFetchAndFilter(
            l -> historyService.recentDistributions(instrument, l),
            decisionAt, limit,
            (DistributionEventView v) -> new OrderFlowEventSummary(
                v.timestamp(), "DISTRIBUTION", v.type(), v.confidenceScore(), v.consecutiveCount()));
    }

    @Override
    public List<OrderFlowEventSummary> recentCycle(Instrument instrument, Instant decisionAt, int limit) {
        return overFetchAndFilter(
            l -> historyService.recentCycles(instrument, l),
            decisionAt, limit,
            (CycleEventView v) -> new OrderFlowEventSummary(
                v.timestamp(), "CYCLE", v.cycleType(), v.confidence(), 0L));
    }

    /**
     * Fetches up to {@code limit × OVER_FETCH_MULTIPLIER} rows so we can drop
     * post-{@code decisionAt} entries without underfilling the requested page.
     * Falls back to a second over-fetch round capped at {@link #OVER_FETCH_MAX}
     * when the first round still didn't yield enough qualifying rows; keeps the
     * window honest while bounding load on the history service.
     */
    private <T> List<OrderFlowEventSummary> overFetchAndFilter(
            java.util.function.IntFunction<List<T>> fetcher,
            Instant decisionAt, int limit,
            java.util.function.Function<T, OrderFlowEventSummary> mapper) {

        int wanted = Math.max(1, limit);
        int firstRound = Math.min(OVER_FETCH_MAX, Math.max(wanted, wanted * OVER_FETCH_MULTIPLIER));
        List<OrderFlowEventSummary> filtered = filter(fetcher.apply(firstRound), decisionAt, mapper);
        if (filtered.size() >= wanted || firstRound >= OVER_FETCH_MAX) {
            return trim(filtered, wanted);
        }
        // Second round at max — only triggered when the first batch was almost
        // entirely post-decision, which is rare but real on bursty markets.
        filtered = filter(fetcher.apply(OVER_FETCH_MAX), decisionAt, mapper);
        return trim(filtered, wanted);
    }

    private <T> List<OrderFlowEventSummary> filter(
            List<T> source, Instant decisionAt,
            java.util.function.Function<T, OrderFlowEventSummary> mapper) {
        List<OrderFlowEventSummary> out = new ArrayList<>(source.size());
        for (T item : source) {
            OrderFlowEventSummary mapped = mapper.apply(item);
            if (mapped.timestamp() != null && !mapped.timestamp().isAfter(decisionAt)) {
                out.add(mapped);
            }
        }
        // Newest first — defensive: history service already orders desc by ts but
        // we don't want to silently rely on an undocumented contract.
        out.sort(Comparator.comparing(OrderFlowEventSummary::timestamp).reversed());
        return out;
    }

    private static List<OrderFlowEventSummary> trim(List<OrderFlowEventSummary> source, int limit) {
        return source.size() <= limit ? source : source.subList(0, limit);
    }
}
