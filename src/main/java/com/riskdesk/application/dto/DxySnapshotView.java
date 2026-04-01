package com.riskdesk.application.dto;

import com.riskdesk.domain.marketdata.model.DxySnapshot;

import java.math.BigDecimal;
import java.time.Instant;

public record DxySnapshotView(
    Instant timestamp,
    BigDecimal eurusd,
    BigDecimal usdjpy,
    BigDecimal gbpusd,
    BigDecimal usdcad,
    BigDecimal usdsek,
    BigDecimal usdchf,
    BigDecimal dxyValue,
    String source,
    boolean isComplete
) {

    public static DxySnapshotView from(DxySnapshot snapshot) {
        return new DxySnapshotView(
            snapshot.timestamp(),
            snapshot.eurusd(),
            snapshot.usdjpy(),
            snapshot.gbpusd(),
            snapshot.usdcad(),
            snapshot.usdsek(),
            snapshot.usdchf(),
            snapshot.dxyValue(),
            snapshot.source(),
            snapshot.complete()
        );
    }
}
