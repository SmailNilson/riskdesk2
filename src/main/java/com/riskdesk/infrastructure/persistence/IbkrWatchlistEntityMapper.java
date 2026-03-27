package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.IbkrWatchlist;
import com.riskdesk.domain.model.IbkrWatchlistInstrument;
import com.riskdesk.infrastructure.persistence.entity.IbkrWatchlistEntity;
import com.riskdesk.infrastructure.persistence.entity.IbkrWatchlistInstrumentEntity;

import java.util.ArrayList;

public final class IbkrWatchlistEntityMapper {

    private IbkrWatchlistEntityMapper() {
    }

    public static IbkrWatchlistEntity toEntity(IbkrWatchlist domain) {
        IbkrWatchlistEntity entity = new IbkrWatchlistEntity();
        entity.setId(domain.getId());
        entity.setWatchlistId(domain.getWatchlistId());
        entity.setName(domain.getName());
        entity.setReadOnly(domain.isReadOnly());
        entity.setImportedAt(domain.getImportedAt());
        entity.setInstruments(new ArrayList<>());

        for (IbkrWatchlistInstrument instrument : domain.getInstruments()) {
            IbkrWatchlistInstrumentEntity instrumentEntity = toEntity(instrument);
            instrumentEntity.setWatchlist(entity);
            entity.getInstruments().add(instrumentEntity);
        }
        return entity;
    }

    public static IbkrWatchlist toDomain(IbkrWatchlistEntity entity) {
        IbkrWatchlist domain = new IbkrWatchlist();
        domain.setId(entity.getId());
        domain.setWatchlistId(entity.getWatchlistId());
        domain.setName(entity.getName());
        domain.setReadOnly(entity.isReadOnly());
        domain.setImportedAt(entity.getImportedAt());
        domain.setInstruments(entity.getInstruments().stream().map(IbkrWatchlistEntityMapper::toDomain).toList());
        return domain;
    }

    private static IbkrWatchlistInstrumentEntity toEntity(IbkrWatchlistInstrument domain) {
        IbkrWatchlistInstrumentEntity entity = new IbkrWatchlistInstrumentEntity();
        entity.setId(domain.getId());
        entity.setPositionIndex(domain.getPositionIndex());
        entity.setConid(domain.getConid());
        entity.setSymbol(domain.getSymbol());
        entity.setLocalSymbol(domain.getLocalSymbol());
        entity.setName(domain.getName());
        entity.setAssetClass(domain.getAssetClass());
        entity.setInstrumentCode(domain.getInstrumentCode());
        return entity;
    }

    private static IbkrWatchlistInstrument toDomain(IbkrWatchlistInstrumentEntity entity) {
        IbkrWatchlistInstrument domain = new IbkrWatchlistInstrument();
        domain.setId(entity.getId());
        domain.setPositionIndex(entity.getPositionIndex());
        domain.setConid(entity.getConid());
        domain.setSymbol(entity.getSymbol());
        domain.setLocalSymbol(entity.getLocalSymbol());
        domain.setName(entity.getName());
        domain.setAssetClass(entity.getAssetClass());
        domain.setInstrumentCode(entity.getInstrumentCode());
        return domain;
    }
}
