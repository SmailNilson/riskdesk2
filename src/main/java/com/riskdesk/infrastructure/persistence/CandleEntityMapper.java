package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.infrastructure.persistence.entity.CandleEntity;

public final class CandleEntityMapper {

    private CandleEntityMapper() {
    }

    public static CandleEntity toEntity(Candle candle) {
        CandleEntity entity = new CandleEntity();
        entity.setId(candle.getId());
        entity.setInstrument(candle.getInstrument());
        entity.setTimeframe(candle.getTimeframe());
        entity.setTimestamp(candle.getTimestamp());
        entity.setOpen(candle.getOpen());
        entity.setHigh(candle.getHigh());
        entity.setLow(candle.getLow());
        entity.setClose(candle.getClose());
        entity.setVolume(candle.getVolume());
        return entity;
    }

    public static Candle toDomain(CandleEntity entity) {
        Candle candle = new Candle();
        candle.setId(entity.getId());
        candle.setInstrument(entity.getInstrument());
        candle.setTimeframe(entity.getTimeframe());
        candle.setTimestamp(entity.getTimestamp());
        candle.setOpen(entity.getOpen());
        candle.setHigh(entity.getHigh());
        candle.setLow(entity.getLow());
        candle.setClose(entity.getClose());
        candle.setVolume(entity.getVolume());
        return candle;
    }
}
