package com.riskdesk.domain.engine.playbook.agent;

/**
 * Marker for deterministic, non-AI agents whose verdict can <b>hard-block</b> a setup.
 *
 * <p>Gates run <b>sequentially and first</b> in {@code AgentOrchestratorService}.
 * The moment any gate returns a verdict whose {@link AgentAdjustments#blocked()}
 * is true, the orchestrator short-circuits and does not call any
 * {@link Scorer} — saving the latency and API cost of Gemini calls on setups
 * that are already disqualified (market closed, maintenance window, etc.).
 *
 * <p>Gates are expected to be microsecond-fast and free of external I/O.
 * If you find yourself wanting a gate that calls Gemini, it is probably a
 * {@link Scorer} that happens to have a strong-opinion output — keep Gates
 * simple and local.
 */
public interface Gate extends TradingAgent {
}
