package com.riskdesk.domain.marketdata.model;

import java.time.Instant;

/**
 * Session-anchored Cumulative Volume Delta (CVD): the running sum of signed
 * classified volume (buy − sell) since the current session anchor.
 *
 * <p>Anchor selection (via {@code TradingSessionResolver}):</p>
 * <ul>
 *   <li>{@code RTH} — inside US Regular Trading Hours (09:30–16:00 ET) the CVD is
 *       anchored at the RTH open (09:30 ET) of the current day.</li>
 *   <li>{@code GLOBEX} — outside RTH the CVD is anchored at the CME Globex daily
 *       session start (17:00 ET, equivalent to the 18:00 ET re-open because no trades
 *       print during the 17:00–18:00 ET maintenance halt).</li>
 * </ul>
 *
 * @param value       the cumulative signed volume since {@code anchorStart}
 * @param anchor      {@link #ANCHOR_RTH} or {@link #ANCHOR_GLOBEX}
 * @param anchorStart UTC instant of the session anchor the value accumulates from
 */
public record SessionCvd(long value, String anchor, Instant anchorStart) {

    /** CVD anchored at the RTH open (09:30 ET). */
    public static final String ANCHOR_RTH = "RTH";
    /** CVD anchored at the CME Globex daily session start (17:00 ET). */
    public static final String ANCHOR_GLOBEX = "GLOBEX";
}
