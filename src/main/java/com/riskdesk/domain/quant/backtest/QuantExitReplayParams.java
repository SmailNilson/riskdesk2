package com.riskdesk.domain.quant.backtest;

import com.riskdesk.domain.quant.simulation.QuantSimExitPolicy;
import com.riskdesk.domain.quant.simulation.QuantSimStopMode;

import java.time.LocalTime;

/**
 * Parameter bundle for {@link QuantExitReplayEngine} — one exit-policy variant
 * to replay over the recorded Quant 7-Gates entry signals.
 *
 * @param stopMode             FIXED point offsets or ATR multiples
 * @param fixedSlPts           SL offset in points (FIXED mode)
 * @param fixedTpPts           TP offset in points (FIXED mode)
 * @param atrPeriod            ATR look-back (5m resample, Wilder smoothing)
 * @param slAtrMult            SL offset in ATR multiples (ATR mode)
 * @param tpAtrMult            TP offset in ATR multiples (ATR mode)
 * @param exitPolicy           how the recorded flow-AVOID flip is honoured:
 *                             {@code SLTP_ONLY} ignores it, {@code FLOW_AVOID}
 *                             honours it as recorded, {@code FLOW_AVOID_IN_PROFIT}
 *                             honours it only when the trade is profitable then
 * @param htfFilter            require 1h EMA fast/slow alignment at entry
 * @param htfEmaFast           fast EMA period (hourly closes)
 * @param htfEmaSlow           slow EMA period (hourly closes)
 * @param commissionPerTradeUsd round-trip commission+fees debited per replayed trade
 * @param eodFlatEt            ET wall-clock time the replay flattens at the candle close
 */
public record QuantExitReplayParams(
    QuantSimStopMode stopMode,
    double fixedSlPts,
    double fixedTpPts,
    int atrPeriod,
    double slAtrMult,
    double tpAtrMult,
    QuantSimExitPolicy exitPolicy,
    boolean htfFilter,
    int htfEmaFast,
    int htfEmaSlow,
    double commissionPerTradeUsd,
    LocalTime eodFlatEt
) {

    /** The calibrated default variant (ATR 2.0/3.0, SL/TP only, HTF on). */
    public static QuantExitReplayParams calibratedDefaults() {
        return new QuantExitReplayParams(
            QuantSimStopMode.ATR, 25.0, 40.0,
            14, 2.0, 3.0,
            QuantSimExitPolicy.SLTP_ONLY,
            true, 20, 50,
            1.24, LocalTime.of(16, 55));
    }
}
