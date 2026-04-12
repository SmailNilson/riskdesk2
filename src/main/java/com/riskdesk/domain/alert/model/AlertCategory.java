package com.riskdesk.domain.alert.model;

public enum AlertCategory {
    RISK, EMA, RSI, MACD, SMC, ORDER_BLOCK, ORDER_BLOCK_VWAP, WAVETREND, SIGNAL,
    SUPERTREND, BOLLINGER, VWAP_CROSS, FVG, EQUAL_LEVEL, DELTA_FLOW, CHAIKIN, MTF_LEVEL,
    EMA_PROXIMITY, SUPPORT_RESISTANCE, CHAIKIN_BEHAVIOUR, STOCHASTIC,
    /** Cross-instrument correlation signal (e.g. Oil-Nasdaq Inverse Momentum Scalp — ONIMS). */
    CROSS_INSTRUMENT,
    /** Real delta oscillator cross-zero (EMA(3)-EMA(10) of tick-by-tick delta). UC-OF-007. */
    DELTA_OSCILLATOR,
    /** Institutional absorption detected (high delta + stable price + high volume). UC-OF-004. */
    ABSORPTION
}
