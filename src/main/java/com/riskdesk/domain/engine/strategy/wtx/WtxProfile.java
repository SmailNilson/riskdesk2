package com.riskdesk.domain.engine.strategy.wtx;

/**
 * WTX strategy profile — mirrors the Pine Script profile selector.
 *
 * BASELINE     — Raw WT_X signals (legacy behaviour). No risk gating beyond NY force-close.
 * SESSION_ATR  — Adds maxDailyLoss gating + ATR trailing exits.
 * HTF          — Above + HTF bias filter (EMA21/55 on configurable higher timeframe).
 * STRICT       — Above + structure proxy (sweep+reclaim / break+reclaim).
 *
 * Profile is stored per-instrument so users can mix conservative and exploratory profiles.
 */
public enum WtxProfile {
    BASELINE,
    SESSION_ATR,
    HTF,
    STRICT;

    public boolean blocksOnMaxLoss() {
        return this != BASELINE;
    }

    public boolean requiresAtrExits() {
        return this != BASELINE;
    }

    public boolean requiresHtfFilter() {
        return this == HTF || this == STRICT;
    }

    public boolean requiresStructureFilter() {
        return this == STRICT;
    }
}
