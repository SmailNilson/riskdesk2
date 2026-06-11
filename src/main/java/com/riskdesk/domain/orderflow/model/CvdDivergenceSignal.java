package com.riskdesk.domain.orderflow.model;

import java.time.Instant;

/**
 * Confirmed price/CVD pivot divergence (UC-OF-CVD).
 *
 * <ul>
 *   <li>{@code BEARISH_DIVERGENCE} — price printed a higher swing-high while the
 *       session CVD printed a lower swing-high (buyers exhausting into the push).</li>
 *   <li>{@code BULLISH_DIVERGENCE} — price printed a lower swing-low while the
 *       session CVD printed a higher swing-low (sellers exhausting into the flush).</li>
 * </ul>
 *
 * @param type           {@link #BEARISH} or {@link #BULLISH}
 * @param prevPivotPrice price close at the previous confirmed pivot
 * @param newPivotPrice  price close at the newly confirmed pivot
 * @param prevPivotCvd   session CVD at the previous confirmed pivot
 * @param newPivotCvd    session CVD at the newly confirmed pivot
 * @param pivotTimestamp open time of the 1m bar that formed the new pivot
 */
public record CvdDivergenceSignal(
    String type,
    double prevPivotPrice,
    double newPivotPrice,
    long prevPivotCvd,
    long newPivotCvd,
    Instant pivotTimestamp
) {
    public static final String BEARISH = "BEARISH_DIVERGENCE";
    public static final String BULLISH = "BULLISH_DIVERGENCE";
}
