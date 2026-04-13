package com.riskdesk.domain.engine.playbook.agent;

import com.riskdesk.domain.engine.playbook.model.PlaybookInput;
import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;

public record AgentContext(
    Instrument instrument,
    String timeframe,
    PlaybookInput input,
    PortfolioState portfolio,
    MacroSnapshot macro,
    BigDecimal atr
) {
    public record PortfolioState(
        double totalUnrealizedPnL,
        double dailyDrawdownPct,
        int openPositionCount,
        boolean hasCorrelatedPosition
    ) {
        public static PortfolioState empty() {
            return new PortfolioState(0, 0, 0, false);
        }
    }

    public record MacroSnapshot(
        Double dxyPctChange,
        String dxyTrend,
        String sessionPhase,
        boolean isKillZone
    ) {
        public static MacroSnapshot empty() {
            return new MacroSnapshot(null, null, null, false);
        }
    }
}
