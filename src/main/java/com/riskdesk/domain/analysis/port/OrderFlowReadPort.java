package com.riskdesk.domain.analysis.port;

import com.riskdesk.domain.analysis.model.OrderFlowContext;
import com.riskdesk.domain.analysis.model.OrderFlowEventSummary;
import com.riskdesk.domain.model.Instrument;

import java.time.Instant;
import java.util.List;

/**
 * Read-only access to order-flow state for the analysis pipeline.
 * <p>
 * All event lists MUST be filtered to {@code event.timestamp <= decisionAt} —
 * this is the contract that prevents look-ahead bias in the scoring engine.
 */
public interface OrderFlowReadPort {

    record TimedOrderFlow(OrderFlowContext context, Instant asOf) {}

    TimedOrderFlow contextAsOf(Instrument instrument, Instant decisionAt);

    List<OrderFlowEventSummary> recentMomentum(Instrument instrument, Instant decisionAt, int limit);

    List<OrderFlowEventSummary> recentAbsorption(Instrument instrument, Instant decisionAt, int limit);

    List<OrderFlowEventSummary> recentDistribution(Instrument instrument, Instant decisionAt, int limit);

    List<OrderFlowEventSummary> recentCycle(Instrument instrument, Instant decisionAt, int limit);
}
