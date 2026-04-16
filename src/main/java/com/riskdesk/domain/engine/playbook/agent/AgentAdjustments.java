package com.riskdesk.domain.engine.playbook.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Typed replacement for the previous {@code Map<String, Object>} used as agent
 * adjustments. The two structural levers that the orchestrator acts on
 * (size cap, hard block) are explicit fields; everything else agents or Gemini
 * emit (e.g. {@code counter_trend}, {@code htf_fake_break}, {@code wall_blocking_tp})
 * stays in {@link #extraFlags()} where the resolver can read it opportunistically
 * without casting.
 *
 * <p>This removes the {@code (Number) v.adjustments().get("size_pct")} casts
 * from {@code AgentOrchestratorService.resolveVerdicts} — the only structural
 * fields are now compile-time-safe.
 */
public record AgentAdjustments(
    Optional<Double> sizePctCap,
    boolean blocked,
    Map<String, Object> extraFlags
) {

    public AgentAdjustments {
        if (sizePctCap == null) sizePctCap = Optional.empty();
        extraFlags = extraFlags == null ? Map.of() : Map.copyOf(extraFlags);
    }

    public static AgentAdjustments none() {
        return new AgentAdjustments(Optional.empty(), false, Map.of());
    }

    public static AgentAdjustments block() {
        return new AgentAdjustments(Optional.empty(), true, Map.of());
    }

    public static AgentAdjustments blockWith(Map<String, Object> extras) {
        return new AgentAdjustments(Optional.empty(), true, extras != null ? extras : Map.of());
    }

    public static AgentAdjustments sizeCap(double pct) {
        return new AgentAdjustments(Optional.of(pct), false, Map.of());
    }

    public static AgentAdjustments flags(Map<String, Object> extras) {
        return new AgentAdjustments(Optional.empty(), false, extras != null ? extras : Map.of());
    }

    /**
     * Parses a Gemini {@code flags} object, extracting {@code size_pct} and
     * {@code blocked} into structural fields and preserving everything else
     * under {@link #extraFlags()}. Guards against malformed types.
     */
    public static AgentAdjustments fromGeminiFlags(Map<String, Object> flags) {
        if (flags == null || flags.isEmpty()) return none();
        Optional<Double> size = Optional.empty();
        boolean block = false;
        Map<String, Object> extras = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : flags.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();
            if ("size_pct".equals(key) && val instanceof Number n) {
                size = Optional.of(n.doubleValue());
            } else if ("blocked".equals(key) && Boolean.TRUE.equals(val)) {
                block = true;
            } else {
                extras.put(key, val);
            }
        }
        return new AgentAdjustments(size, block, extras);
    }
}
