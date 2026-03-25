package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Position;
import com.riskdesk.infrastructure.persistence.entity.PositionEntity;

public final class PositionEntityMapper {

    private PositionEntityMapper() {
    }

    public static PositionEntity toEntity(Position position) {
        PositionEntity entity = new PositionEntity();
        entity.setId(position.getId());
        entity.setInstrument(position.getInstrument());
        entity.setSide(position.getSide());
        entity.setQuantity(position.getQuantity());
        entity.setEntryPrice(position.getEntryPrice());
        entity.setStopLoss(position.getStopLoss());
        entity.setTakeProfit(position.getTakeProfit());
        entity.setCurrentPrice(position.getCurrentPrice());
        entity.setUnrealizedPnL(position.getUnrealizedPnL());
        entity.setOpen(position.isOpen());
        entity.setOpenedAt(position.getOpenedAt());
        entity.setClosedAt(position.getClosedAt());
        entity.setRealizedPnL(position.getRealizedPnL());
        entity.setNotes(position.getNotes());
        return entity;
    }

    public static Position toDomain(PositionEntity entity) {
        Position position = new Position();
        position.setId(entity.getId());
        position.setInstrument(entity.getInstrument());
        position.setSide(entity.getSide());
        position.setQuantity(entity.getQuantity());
        position.setEntryPrice(entity.getEntryPrice());
        position.setStopLoss(entity.getStopLoss());
        position.setTakeProfit(entity.getTakeProfit());
        position.setCurrentPrice(entity.getCurrentPrice());
        position.setUnrealizedPnL(entity.getUnrealizedPnL());
        position.setOpen(entity.isOpen());
        position.setOpenedAt(entity.getOpenedAt());
        position.setClosedAt(entity.getClosedAt());
        position.setRealizedPnL(entity.getRealizedPnL());
        position.setNotes(entity.getNotes());
        return position;
    }
}
