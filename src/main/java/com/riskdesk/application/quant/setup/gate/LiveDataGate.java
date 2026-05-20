package com.riskdesk.application.quant.setup.gate;

import com.riskdesk.domain.quant.setup.GateCheckResult;
import com.riskdesk.domain.quant.setup.SetupEvaluationContext;
import com.riskdesk.domain.quant.setup.SetupGate;

/**
 * Passes only when the snapshot carries a live price and delta.
 * Without these the engine cannot compute entry/SL/TP levels.
 */
public class LiveDataGate implements SetupGate {

    @Override
    public GateCheckResult check(SetupEvaluationContext ctx) {
        if (ctx.snapshot().price() == null) {
            return GateCheckResult.fail("LIVE_DATA", "no live price available");
        }
        return GateCheckResult.pass("LIVE_DATA", "price=" + ctx.snapshot().price());
    }
}
