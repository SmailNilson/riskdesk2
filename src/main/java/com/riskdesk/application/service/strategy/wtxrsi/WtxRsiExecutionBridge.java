package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingResult;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiRiskPlan;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignal;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignalRecord;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiStrategyState;

import java.math.BigDecimal;

/**
 * Routes a WTX+RSI strategy action to IBKR.
 *
 * <p>The bridge is the only seam where the strategy touches a broker. The service
 * pulls it via {@code ObjectProvider<WtxRsiExecutionBridge>}: when the bean is
 * not wired (IBKR mode off, or the user has flipped {@code autoExecutionEnabled=false}
 * for this row), the service short-circuits with
 * {@link com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome#SKIPPED_BRIDGE_UNAVAILABLE}
 * or {@code SKIPPED_AUTO_OFF}.
 *
 * <p>{@code WtxRoutingResult} is reused from the existing WTx pipeline so the UI and
 * persistence layer treat both strategies uniformly.
 */
public interface WtxRsiExecutionBridge {

    /**
     * Submit an OPEN order for the given signal + plan.
     * The implementation must persist an execution row keyed by the signal timestamp
     * before talking to the broker, to keep CLOSE legs idempotent.
     */
    WtxRoutingResult submitOpen(
            WtxRsiSignal signal,
            WtxRsiRiskPlan plan,
            WtxRsiStrategyState state,
            BigDecimal referencePrice);

    /**
     * Submit a CLOSE order to flatten an existing open position.
     * {@code action} carries the close direction (CLOSE_LONG / CLOSE_SHORT) for logging
     * and execution-row tagging.
     */
    WtxRoutingResult submitClose(
            WtxRsiStrategyState state,
            WtxRsiSignalRecord.Action action,
            BigDecimal referencePrice);
}
