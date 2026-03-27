package com.riskdesk.infrastructure.persistence.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ibkr_watchlists")
public class IbkrWatchlistEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "watchlist_id", nullable = false, unique = true, length = 64)
    private String watchlistId;

    @Column(nullable = false, length = 256)
    private String name;

    @Column(nullable = false)
    private boolean readOnly;

    @Column(nullable = false)
    private Instant importedAt;

    @OneToMany(mappedBy = "watchlist", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("positionIndex ASC")
    private List<IbkrWatchlistInstrumentEntity> instruments = new ArrayList<>();

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

    public List<IbkrWatchlistInstrumentEntity> getInstruments() {
        return instruments;
    }

    public void setInstruments(List<IbkrWatchlistInstrumentEntity> instruments) {
        this.instruments = instruments;
    }
}
