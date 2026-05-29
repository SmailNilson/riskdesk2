package com.riskdesk.domain.engine.strategy.wtxrsi;

import java.math.BigDecimal;

/**
 * Immutable strategy parameters. Single source of truth for the
 * "variables to optimise" enumerated in the brief.
 *
 * @param wtN1             WaveTrend channel length (10 default)
 * @param wtN2             WaveTrend average length (21 default)
 * @param wtSignalPeriod   WT2 SMA period (4 default)
 * @param wtOverbought     OB band (53 default)
 * @param wtOversold       OS band (-53 default)
 * @param rsiLength        RSI period (14 default)
 * @param rsiSmaLength     SMA-on-RSI period (14 default)
 * @param syncLookbackBars X — # of bars AFTER the WT cross within which the
 *                         RSI/SMA cross must occur (0 = same bar only).
 *                         Causal — no look-back.
 * @param zoneMode         How strictly the WT cross must relate to OB/OS zone.
 * @param zoneLookbackBars Used by {@link WtxRsiZoneMode#VISITED_RECENTLY}.
 * @param fractalLeftRight Williams fractal pivot leftRight (Y in the brief).
 * @param fractalMaxLookback Maximum age of an acceptable confirmed fractal
 *                         (in bars) when building the SL. Falls back to no-trade
 *                         if no fractal is found in this window.
 * @param swingBufferTicks Ticks to add beyond the fractal price for the actual SL.
 * @param tickSize         Instrument tick size (MNQ = 0.25)
 * @param tickValueUsd     USD value per tick per contract (MNQ = 0.50)
 * @param baseContracts    Default position size (1)
 * @param confirmedMultiplier Multiplier on Chaikin confirmation (2)
 * @param tpMode           REVERSAL (close on opposite signal) or R_MULTIPLE
 * @param tpRMultiple      If tpMode = R_MULTIPLE, target = R × initial risk.
 * @param chaikinFast      Chaikin oscillator EMA-fast (3)
 * @param chaikinSlow      Chaikin oscillator EMA-slow (10)
 * @param chaikinEnabled   Whether confirmation should be applied at all.
 * @param biasSource       Which engine resolves the swing bias used by the
 *                         optional {@code swingBiasFilterEnabled} toggle.
 * @param chaikinRequired  When {@code true}, an <i>entry</i> may only open if the
 *                         Chaikin oscillator confirms the signal direction;
 *                         unconfirmed signals are suppressed (no OPEN). Exits
 *                         (reversal-on-opposite-signal and SL/TP) are unaffected.
 *                         Only effective when {@link #chaikinEnabled} is also
 *                         {@code true} — confirmation that is never computed
 *                         would otherwise block every entry. Default {@code false}.
 */
public record WtxRsiConfig(
        int wtN1,
        int wtN2,
        int wtSignalPeriod,
        BigDecimal wtOverbought,
        BigDecimal wtOversold,
        int rsiLength,
        int rsiSmaLength,
        int syncLookbackBars,
        WtxRsiZoneMode zoneMode,
        int zoneLookbackBars,
        int fractalLeftRight,
        int fractalMaxLookback,
        int swingBufferTicks,
        BigDecimal tickSize,
        BigDecimal tickValueUsd,
        int baseContracts,
        int confirmedMultiplier,
        WtxRsiTpMode tpMode,
        BigDecimal tpRMultiple,
        int chaikinFast,
        int chaikinSlow,
        boolean chaikinEnabled,
        WtxRsiBiasSource biasSource,
        boolean chaikinRequired
) {

    /**
     * Backward-compatible constructor — keeps the {@code chaikinRequired} entry
     * gate <b>off</b> for existing call sites (tests, {@code defaults*}) that
     * predate the field. New code that needs the gate uses the canonical
     * constructor with the trailing {@code chaikinRequired} flag.
     */
    public WtxRsiConfig(
            int wtN1, int wtN2, int wtSignalPeriod,
            BigDecimal wtOverbought, BigDecimal wtOversold,
            int rsiLength, int rsiSmaLength,
            int syncLookbackBars,
            WtxRsiZoneMode zoneMode, int zoneLookbackBars,
            int fractalLeftRight, int fractalMaxLookback,
            int swingBufferTicks,
            BigDecimal tickSize, BigDecimal tickValueUsd,
            int baseContracts, int confirmedMultiplier,
            WtxRsiTpMode tpMode, BigDecimal tpRMultiple,
            int chaikinFast, int chaikinSlow, boolean chaikinEnabled,
            WtxRsiBiasSource biasSource) {
        this(wtN1, wtN2, wtSignalPeriod, wtOverbought, wtOversold,
                rsiLength, rsiSmaLength, syncLookbackBars,
                zoneMode, zoneLookbackBars, fractalLeftRight, fractalMaxLookback,
                swingBufferTicks, tickSize, tickValueUsd,
                baseContracts, confirmedMultiplier, tpMode, tpRMultiple,
                chaikinFast, chaikinSlow, chaikinEnabled, biasSource,
                false);
    }

    public static WtxRsiConfig defaults5m() {
        return new WtxRsiConfig(
                10, 21, 4,
                BigDecimal.valueOf(53), BigDecimal.valueOf(-53),
                14, 14,
                3, // X — RSI must cross within 3 bars after the WT cross
                WtxRsiZoneMode.STRICT_ZONE, 5,
                2, 20,  // Y=2 fractal, search up to 20 bars back
                2,                                  // 2 ticks buffer beyond fractal
                new BigDecimal("0.25"), new BigDecimal("0.50"),
                1, 2,
                WtxRsiTpMode.REVERSAL, BigDecimal.ZERO,
                3, 10, true,
                WtxRsiBiasSource.FRACTAL_HH_HL
        );
    }

    public static WtxRsiConfig defaults10m() {
        return new WtxRsiConfig(
                10, 21, 4,
                BigDecimal.valueOf(53), BigDecimal.valueOf(-53),
                14, 14,
                2,
                WtxRsiZoneMode.STRICT_ZONE, 5,
                2, 16,
                2,
                new BigDecimal("0.25"), new BigDecimal("0.50"),
                1, 2,
                WtxRsiTpMode.REVERSAL, BigDecimal.ZERO,
                3, 10, true,
                WtxRsiBiasSource.FRACTAL_HH_HL
        );
    }
}
