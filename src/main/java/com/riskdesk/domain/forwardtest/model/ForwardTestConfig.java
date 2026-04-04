package com.riskdesk.domain.forwardtest.model;

import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Immutable configuration for the forward-test evaluator.
 * Injected into the evaluator so slippage, commissions, TTL, and risk
 * can be tuned without touching business logic.
 *
 * @param riskPct              max risk per trade as fraction of account balance (e.g. 0.01 = 1%)
 * @param slippageTicks        simulated slippage applied to each fill (±1 tick default)
 * @param commissionPerSide    commission per contract per side, keyed by instrument
 * @param ttlSecondsByTimeframe TTL in seconds per alert timeframe — setup expires after this
 */
public record ForwardTestConfig(
        BigDecimal riskPct,
        int slippageTicks,
        Map<Instrument, BigDecimal> commissionPerSide,
        Map<String, Long> ttlSecondsByTimeframe
) {

    private static final BigDecimal DEFAULT_COMMISSION = new BigDecimal("0.62");

    /** Standard defaults: 1% risk, 1 tick slippage, CME micro commissions. */
    public static ForwardTestConfig defaults() {
        return new ForwardTestConfig(
                new BigDecimal("0.01"),
                1,
                Map.of(
                        Instrument.MCL, DEFAULT_COMMISSION,
                        Instrument.MGC, DEFAULT_COMMISSION,
                        Instrument.E6,  DEFAULT_COMMISSION,
                        Instrument.MNQ, DEFAULT_COMMISSION
                ),
                Map.of(
                        "1m",  7200L,    // 2h
                        "5m",  7200L,    // 2h
                        "10m", 14400L,   // 4h
                        "15m", 14400L,   // 4h
                        "30m", 28800L,   // 8h
                        "1h",  28800L,   // 8h
                        "4h",  86400L,   // 24h
                        "1d",  172800L   // 48h
                )
        );
    }

    /** Commission per side for an instrument, defaults to $0.62 if not configured. */
    public BigDecimal commissionFor(Instrument instrument) {
        return commissionPerSide.getOrDefault(instrument, DEFAULT_COMMISSION);
    }

    /** TTL in seconds for a given timeframe, defaults to 8h if not configured. */
    public long ttlSecondsFor(String timeframe) {
        return ttlSecondsByTimeframe.getOrDefault(timeframe, 28800L);
    }
}
