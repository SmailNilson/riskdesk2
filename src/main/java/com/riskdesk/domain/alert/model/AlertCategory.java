package com.riskdesk.domain.alert.model;

public enum AlertCategory {
    RISK, EMA, RSI, MACD, SMC, ORDER_BLOCK, ORDER_BLOCK_VWAP, WAVETREND, SIGNAL,
    SUPERTREND, BOLLINGER, VWAP_CROSS, FVG, EQUAL_LEVEL, DELTA_FLOW, CHAIKIN, MTF_LEVEL,
    /** Cross-instrument correlation signal (e.g. Oil-Nasdaq Inverse Momentum Scalp — ONIMS). */
    CROSS_INSTRUMENT
}
