package com.riskdesk.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "ibkr_watchlist_instruments")
public class IbkrWatchlistInstrumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "watchlist_fk", nullable = false)
    private IbkrWatchlistEntity watchlist;

    @Column(nullable = false)
    private int positionIndex;

    private Long conid;

    @Column(length = 64)
    private String symbol;

    @Column(length = 128)
    private String localSymbol;

    @Column(length = 256)
    private String name;

    @Column(length = 32)
    private String assetClass;

    @Column(length = 32)
    private String instrumentCode;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public IbkrWatchlistEntity getWatchlist() {
        return watchlist;
    }

    public void setWatchlist(IbkrWatchlistEntity watchlist) {
        this.watchlist = watchlist;
    }

    public int getPositionIndex() {
        return positionIndex;
    }

    public void setPositionIndex(int positionIndex) {
        this.positionIndex = positionIndex;
    }

    public Long getConid() {
        return conid;
    }

    public void setConid(Long conid) {
        this.conid = conid;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getLocalSymbol() {
        return localSymbol;
    }

    public void setLocalSymbol(String localSymbol) {
        this.localSymbol = localSymbol;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAssetClass() {
        return assetClass;
    }

    public void setAssetClass(String assetClass) {
        this.assetClass = assetClass;
    }

    public String getInstrumentCode() {
        return instrumentCode;
    }

    public void setInstrumentCode(String instrumentCode) {
        this.instrumentCode = instrumentCode;
    }
}
