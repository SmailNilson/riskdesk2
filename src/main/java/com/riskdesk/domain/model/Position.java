package com.riskdesk.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "positions")
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Instrument instrument;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Side side;

    @Min(1)
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

    // --- Constructors ---

    public Position() {}

    public Position(Instrument instrument, Side side, int quantity, BigDecimal entryPrice) {
        this.instrument = instrument;
        this.side = side;
        this.quantity = quantity;
        this.entryPrice = entryPrice;
        this.openedAt = Instant.now();
    }

    // --- Business logic ---

    public void updatePnL(BigDecimal marketPrice) {
        this.currentPrice = marketPrice;
        this.unrealizedPnL = instrument.calculatePnL(entryPrice, marketPrice, quantity, side);
    }

    public BigDecimal getRiskAmount() {
        if (stopLoss == null) return null;
        return instrument.calculatePnL(entryPrice, stopLoss, quantity, side).abs();
    }

    public BigDecimal getRewardAmount() {
        if (takeProfit == null) return null;
        return instrument.calculatePnL(entryPrice, takeProfit, quantity, side).abs();
    }

    public BigDecimal getRiskRewardRatio() {
        BigDecimal risk = getRiskAmount();
        BigDecimal reward = getRewardAmount();
        if (risk == null || reward == null || risk.compareTo(BigDecimal.ZERO) == 0) return null;
        return reward.divide(risk, 2, java.math.RoundingMode.HALF_UP);
    }

    public void close(BigDecimal exitPrice) {
        this.open = false;
        this.closedAt = Instant.now();
        this.currentPrice = exitPrice;
        this.realizedPnL = instrument.calculatePnL(entryPrice, exitPrice, quantity, side);
        this.unrealizedPnL = BigDecimal.ZERO;
    }

    // --- Getters & Setters ---

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
