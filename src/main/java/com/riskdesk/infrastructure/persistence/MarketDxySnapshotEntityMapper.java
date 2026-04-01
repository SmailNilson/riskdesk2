package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.marketdata.model.DxySnapshot;
import com.riskdesk.infrastructure.persistence.entity.MarketDxySnapshotEntity;

public final class MarketDxySnapshotEntityMapper {

    private MarketDxySnapshotEntityMapper() {
    }

    public static MarketDxySnapshotEntity toEntity(DxySnapshot snapshot) {
        MarketDxySnapshotEntity entity = new MarketDxySnapshotEntity();
        entity.setTimestamp(snapshot.timestamp());
        entity.setEurusd(snapshot.eurusd());
        entity.setUsdjpy(snapshot.usdjpy());
        entity.setGbpusd(snapshot.gbpusd());
        entity.setUsdcad(snapshot.usdcad());
        entity.setUsdsek(snapshot.usdsek());
        entity.setUsdchf(snapshot.usdchf());
        entity.setDxyValue(snapshot.dxyValue());
        entity.setSource(snapshot.source());
        entity.setComplete(snapshot.complete());
        return entity;
    }

    public static DxySnapshot toDomain(MarketDxySnapshotEntity entity) {
        return new DxySnapshot(
            entity.getTimestamp(),
            entity.getEurusd(),
            entity.getUsdjpy(),
            entity.getGbpusd(),
            entity.getUsdcad(),
            entity.getUsdsek(),
            entity.getUsdchf(),
            entity.getDxyValue(),
            entity.getSource(),
            entity.isComplete()
        );
    }
}
