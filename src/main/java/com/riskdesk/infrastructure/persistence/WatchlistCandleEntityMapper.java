package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.WatchlistCandle;
import com.riskdesk.infrastructure.persistence.entity.WatchlistCandleEntity;

public final class WatchlistCandleEntityMapper {

    private WatchlistCandleEntityMapper() {
    }

    public static WatchlistCandleEntity toEntity(WatchlistCandle candle) {
        WatchlistCandleEntity entity = new WatchlistCandleEntity();
        entity.setId(candle.getId());
        entity.setInstrumentCode(candle.getInstrumentCode());
        entity.setConid(candle.getConid());
        entity.setTimeframe(candle.getTimeframe());
        entity.setTimestamp(candle.getTimestamp());
        entity.setOpen(candle.getOpen());
        entity.setHigh(candle.getHigh());
        entity.setLow(candle.getLow());
        entity.setClose(candle.getClose());
        entity.setVolume(candle.getVolume());
        return entity;
    }

    public static WatchlistCandle toDomain(WatchlistCandleEntity entity) {
        WatchlistCandle candle = new WatchlistCandle();
        candle.setId(entity.getId());
        candle.setInstrumentCode(entity.getInstrumentCode());
        candle.setConid(entity.getConid());
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
