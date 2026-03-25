package com.riskdesk.infrastructure.persistence.entity;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "positions")
public class PositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Instrument instrument;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Side side;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 12, scale = 5)
    private BigDecimal entryPrice;

    @Column(precision = 12, scale = 5)
    private BigDecimal stopLoss;

    @Column(precision = 12, scale = 5)
    private BigDecimal takeProfit;

    @Column(precision = 12, scale = 5)
    private BigDecimal currentPrice;

    @Column(precision = 12, scale = 2)
    private BigDecimal unrealizedPnL;

    @Column(nullable = false)
    private boolean open = true;

    @Column(nullable = false)
    private Instant openedAt;

    private Instant closedAt;

    @Column(precision = 12, scale = 2)
    private BigDecimal realizedPnL;

    private String notes;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Instrument getInstrument() { return instrument; }
    public void setInstrument(Instrument instrument) { this.instrument = instrument; }
    public Side getSide() { return side; }
    public void setSide(Side side) { this.side = side; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
    public BigDecimal getStopLoss() { return stopLoss; }
    public void setStopLoss(BigDecimal stopLoss) { this.stopLoss = stopLoss; }
    public BigDecimal getTakeProfit() { return takeProfit; }
    public void setTakeProfit(BigDecimal takeProfit) { this.takeProfit = takeProfit; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(BigDecimal currentPrice) { this.currentPrice = currentPrice; }
    public BigDecimal getUnrealizedPnL() { return unrealizedPnL; }
    public void setUnrealizedPnL(BigDecimal unrealizedPnL) { this.unrealizedPnL = unrealizedPnL; }
    public boolean isOpen() { return open; }
    public void setOpen(boolean open) { this.open = open; }
    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public BigDecimal getRealizedPnL() { return realizedPnL; }
    public void setRealizedPnL(BigDecimal realizedPnL) { this.realizedPnL = realizedPnL; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
