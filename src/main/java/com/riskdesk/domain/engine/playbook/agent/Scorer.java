package com.riskdesk.domain.engine.playbook.agent;

/**
 * Marker for agents that contribute a <b>weighted opinion</b> — they cannot
 * hard-block a setup, only cap its size or flag low confidence.
 *
 * <p>Scorers run <b>in parallel</b> in {@code AgentOrchestratorService},
 * after all {@link Gate}s have passed. The resolver enforces the contract:
 * a Scorer that emits {@link AgentAdjustments#blocked()} is ignored by
 * the short-circuit path — the orchestrator only treats {@link Gate} blocks
 * as authoritative. That keeps the cost/latency model predictable:
 * at most one round of Gemini calls per orchestration, and only after
 * every gate has allowed the setup through.
 *
 * <p>The three Gemini-powered agents — MTF Confluence, Order Flow and
 * Zone Quality — are all Scorers.
 */
public interface Scorer extends TradingAgent {
}
