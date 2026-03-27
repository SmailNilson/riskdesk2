package com.riskdesk.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class IbkrWatchlist {

    private Long id;
    private String watchlistId;
    private String name;
    private boolean readOnly;
    private Instant importedAt;
    private List<IbkrWatchlistInstrument> instruments = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getWatchlistId() {
        return watchlistId;
    }

    public void setWatchlistId(String watchlistId) {
        this.watchlistId = watchlistId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public Instant getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(Instant importedAt) {
        this.importedAt = importedAt;
    }

    public List<IbkrWatchlistInstrument> getInstruments() {
        return instruments;
    }

    public void setInstruments(List<IbkrWatchlistInstrument> instruments) {
        this.instruments = instruments == null ? new ArrayList<>() : new ArrayList<>(instruments);
    }
}
