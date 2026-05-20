package com.riskdesk.domain.quant.memory;

import com.riskdesk.domain.quant.pattern.OrderFlowPattern;

import java.time.LocalDate;
import java.util.Map;

/**
 * Same-session aggregate fed to the advisor: how many scans we have run today,
 * which patterns we have observed, the win rate of the trades that the user
 * actually executed and a hint about the most recent setup outcome.
 */
public record SessionMemory(
    LocalDate currentDay,
    int scansCount,
    Map<OrderFlowPattern, Integer> patternsObserved,
    int tradesExecuted,
    double winRate,
    String lastSetupOutcome,
    String recentAbnormalities
) {
    public SessionMemory {
        patternsObserved = patternsObserved == null ? Map.of() : Map.copyOf(patternsObserved);
    }

    public static SessionMemory empty(LocalDate day) {
        return new SessionMemory(day, 0, Map.of(), 0, 0.0, "", "");
    }
}
