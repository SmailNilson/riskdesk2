package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingResult;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiRiskPlan;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignal;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignalRecord;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiStrategyState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Test double for {@link WtxRsiExecutionBridge} — records every call. */
final class RecordingWtxRsiBridge implements WtxRsiExecutionBridge {

    final List<OpenCall> opens = new ArrayList<>();
    final List<CloseCall> closes = new ArrayList<>();
    WtxRoutingResult openOutcome = WtxRoutingResult.of(WtxRoutingOutcome.ROUTED);
    WtxRoutingResult closeOutcome = WtxRoutingResult.of(WtxRoutingOutcome.ROUTED);

    @Override
    public WtxRoutingResult submitOpen(
            WtxRsiSignal signal, WtxRsiRiskPlan plan,
            WtxRsiStrategyState state, BigDecimal referencePrice) {
        opens.add(new OpenCall(signal, plan, state, referencePrice));
        return openOutcome;
    }

    @Override
    public WtxRoutingResult submitClose(
            WtxRsiStrategyState state, WtxRsiSignalRecord.Action action, BigDecimal referencePrice) {
        closes.add(new CloseCall(state, action, referencePrice));
        return closeOutcome;
    }

    record OpenCall(WtxRsiSignal signal, WtxRsiRiskPlan plan,
                    WtxRsiStrategyState state, BigDecimal referencePrice) {}

    record CloseCall(WtxRsiStrategyState state, WtxRsiSignalRecord.Action action,
                     BigDecimal referencePrice) {}
}
