package com.riskdesk.application.service.analysis.adapter;

import com.riskdesk.application.dto.AbsorptionEventView;
import com.riskdesk.application.dto.CycleEventView;
import com.riskdesk.application.dto.DistributionEventView;
import com.riskdesk.application.dto.MomentumEventView;
import com.riskdesk.application.service.OrderFlowHistoryService;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Bridges live order-flow runtime state ({@code TickDataPort}, {@code MarketDepthPort})
 * and persisted detector events ({@code OrderFlowHistoryService}) to the
 * domain-side {@link OrderFlowReadPort}.
 * <p>
 * Look-ahead protection: every event list is filtered to
 * {@code event.timestamp <= decisionAt}.
 */
@Component
public class OrderFlowReadAdapter implements OrderFlowReadPort {

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
        return filterAndMap(historyService.recentMomentumBursts(instrument, limit), decisionAt,
            (MomentumEventView v) -> new OrderFlowEventSummary(
                v.timestamp(), "MOMENTUM", v.side(), v.momentumScore(), v.aggressiveDelta()));
    }

    @Override
    public List<OrderFlowEventSummary> recentAbsorption(Instrument instrument, Instant decisionAt, int limit) {
        return filterAndMap(historyService.recentAbsorptions(instrument, limit), decisionAt,
            (AbsorptionEventView v) -> new OrderFlowEventSummary(
                v.timestamp(), "ABSORPTION", v.side(), v.absorptionScore(), v.aggressiveDelta()));
    }

    @Override
    public List<OrderFlowEventSummary> recentDistribution(Instrument instrument, Instant decisionAt, int limit) {
        return filterAndMap(historyService.recentDistributions(instrument, limit), decisionAt,
            (DistributionEventView v) -> new OrderFlowEventSummary(
                v.timestamp(), "DISTRIBUTION", v.type(), v.confidenceScore(), v.consecutiveCount()));
    }

    @Override
    public List<OrderFlowEventSummary> recentCycle(Instrument instrument, Instant decisionAt, int limit) {
        return filterAndMap(historyService.recentCycles(instrument, limit), decisionAt,
            (CycleEventView v) -> new OrderFlowEventSummary(
                v.timestamp(), "CYCLE", v.cycleType(), v.confidence(), 0L));
    }

    private <T> List<OrderFlowEventSummary> filterAndMap(
            List<T> source, Instant decisionAt,
            java.util.function.Function<T, OrderFlowEventSummary> mapper) {
        return source.stream()
            .map(mapper)
            .filter(s -> !s.timestamp().isAfter(decisionAt))
            .toList();
    }
}
