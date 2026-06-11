package com.riskdesk.domain.engine.strategy.wtx;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

/**
 * Per-(instrument, timeframe) frontend overrides of WTX indicator + stop + signal-zone parameters.
 *
 * <p>Each field is nullable: a {@code null} means "no override — fall back to the global
 * {@link WtxConfig} value (from application.properties)". This lets the operator tune
 * {@code n1 / n2 / signalPeriod} (WaveTrend periods), {@code slAtrMult} (initial-stop ATR
 * multiple) and the signal-zone gating ({@code nsc / nsv} overbought/oversold levels +
 * {@code useCompra1 / useVenta1} every-cross flags) per panel without touching the global
 * config, persisted independently of the runtime {@link WtxStrategyState}.
 *
 * <p>Zone-only entries: {@code useCompra1=false} and {@code useVenta1=false} restrict entries to
 * crosses occurring inside the {@code nsc}/{@code nsv} zones (the COMPRA/VENTA signal types),
 * silencing mid-range crosses entirely.
 *
 * <p>{@code sessionFilterEnabled} overrides the GLOBAL session entry filter for this panel only
 * ({@code false} = trade around the clock; the blocked-window boundaries stay global). The
 * top-train-Z35 study was validated session-OFF — this field lets the variant carry that without
 * touching the legacy panels' 03:00-08:00 ET protection.
 */
public record WtxParamOverride(
        Integer n1,
        Integer n2,
        Integer signalPeriod,
        BigDecimal slAtrMult,
        BigDecimal nsc,
        BigDecimal nsv,
        Boolean useCompra1,
        Boolean useVenta1,
        Boolean sessionFilterEnabled
) {
    /** No overrides — every effective value falls back to the global config. */
    public static final WtxParamOverride NONE =
            new WtxParamOverride(null, null, null, null, null, null, null, null, null);

    /**
     * Named preset {@code top-train-Z35} — the zone-entry configuration that came out of the
     * real-1m grid study (MNQ 10m): WaveTrend 5/14/2, initial stop 4.0×ATR (SL_ONLY ride),
     * entries only on crosses inside the ±35 zone (every-cross flags off). The NY force-close
     * still applies.
     *
     * <p>The session gate is deliberately NOT part of the preset (null = inherit the global
     * config, which ships session ON). The original selection (mars→juin, pre-front-month-fix
     * data) favoured session OFF, but the full-period study on real front data (jan→juin 2026)
     * reversed that verdict: session ON keeps ~the same net (+$9,980 vs +$9,768) with maxDD ÷3.5
     * ($1,614 vs $5,639) and survives the directionless winter months the selection never saw.
     * Policy: auto-trading panels default to session ON; opt out per panel via the Session
     * button (a per-panel {@code sessionFilterEnabled=false} override).</p>
     */
    public static final WtxParamOverride TOP_TRAIN_Z35 = new WtxParamOverride(
            5, 14, 2, new BigDecimal("4.0"),
            BigDecimal.valueOf(35), BigDecimal.valueOf(-35), Boolean.FALSE, Boolean.FALSE,
            null);

    /**
     * Resolve a named preset (case/whitespace-insensitive). {@code "clear"} (or {@code "none"})
     * maps to {@link #NONE} so one endpoint both applies and removes presets. Unknown names
     * resolve to {@link Optional#empty()} — the caller decides how to surface the error.
     */
    public static Optional<WtxParamOverride> preset(String name) {
        if (name == null) return Optional.empty();
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "top-train-z35" -> Optional.of(TOP_TRAIN_Z35);
            case "clear", "none" -> Optional.of(NONE);
            default -> Optional.empty();
        };
    }

    public boolean isEmpty() {
        return n1 == null && n2 == null && signalPeriod == null && slAtrMult == null
                && nsc == null && nsv == null && useCompra1 == null && useVenta1 == null
                && sessionFilterEnabled == null;
    }
}
