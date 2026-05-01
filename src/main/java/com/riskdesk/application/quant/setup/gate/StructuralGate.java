package com.riskdesk.application.quant.setup.gate;

import com.riskdesk.domain.quant.setup.GateCheckResult;
import com.riskdesk.domain.quant.setup.SetupEvaluationContext;
import com.riskdesk.domain.quant.setup.SetupGate;

/**
 * Blocks setups when both directions are structurally vetoed.
 * If at least one direction is unblocked the gate passes — the orchestrator
 * later picks the non-blocked direction.
 */
public class StructuralGate implements SetupGate {

    @Override
    public GateCheckResult check(SetupEvaluationContext ctx) {
        boolean shortBlocked = ctx.snapshot().shortBlocked();
        boolean longBlocked  = ctx.snapshot().longBlocked();
        if (shortBlocked && longBlocked) {
            return GateCheckResult.fail("STRUCTURAL",
                "both directions blocked — shortBlocks=" + ctx.snapshot().structuralBlocks()
                + " longBlocks=" + ctx.snapshot().longStructuralBlocks());
        }
        return GateCheckResult.pass("STRUCTURAL",
            "shortBlocked=" + shortBlocked + " longBlocked=" + longBlocked);
    }
}
