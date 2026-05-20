package com.riskdesk.application.quant.setup.gate;

import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.quant.setup.GateCheckResult;
import com.riskdesk.domain.quant.setup.SetupEvaluationContext;
import com.riskdesk.domain.quant.setup.SetupGate;

/**
 * Blocks CHOPPY and UNKNOWN regimes. Both styles trade poorly in incoherent
 * price action; the gate ensures at least TRENDING or RANGING is confirmed.
 */
public class RegimeGate implements SetupGate {

    @Override
    public GateCheckResult check(SetupEvaluationContext ctx) {
        if (ctx.indicators() == null) {
            return GateCheckResult.pass("REGIME", "indicators unavailable — allowing through (degraded mode)");
        }
        String regimeLabel = ctx.indicators().swingBias();
        MarketRegime regime = MarketRegime.fromDetectorLabel(regimeLabel);
        if (regime == MarketRegime.CHOPPY || regime == MarketRegime.UNKNOWN) {
            return GateCheckResult.fail("REGIME", "regime=" + regime + " — no tradable structure");
        }
        return GateCheckResult.pass("REGIME", "regime=" + regime);
    }
}
