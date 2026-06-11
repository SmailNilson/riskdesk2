package com.riskdesk.domain.orderflow.model;

import java.time.Instant;

/**
 * A flagged outsized trade print (UC-OF-BIGPRINT).
 *
 * <p><b>Caveat (IBKR AllLast semantics):</b> IBKR pre-aggregates simultaneous
 * same-price executions into a single AllLast print, so a "print" here is a
 * <i>match event</i>, not a literal order. A flagged big print is therefore closer
 * to sweep detection (one aggressive order clearing multiple resting orders at a
 * level) — which is the desirable signal — rather than a literal single block order.</p>
 *
 * @param price      trade price
 * @param size       print size in contracts
 * @param side       "BUY" or "SELL" (aggressor side, Lee-Ready / tick-rule classified)
 * @param percentile the print's percentile within the rolling size distribution (0..1)
 * @param timestamp  trade time
 */
public record BigPrint(
    double price,
    long size,
    String side,
    double percentile,
    Instant timestamp
) {}
