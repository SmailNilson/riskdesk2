package com.riskdesk.domain.contract.event;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;

/**
 * Domain event published when a futures contract rollover is confirmed.
 * Listeners use this to clean up stale IBKR subscriptions, flush candle
 * accumulators, trigger historical backfill on the new contract, and
 * reset indicator/alert state that would otherwise produce false signals
 * from the old-to-new contract price gap.
 */
public record ContractRolloverEvent(
        Instrument instrument,
        String oldContractMonth,
        String newContractMonth,
        Instant timestamp
) {}
