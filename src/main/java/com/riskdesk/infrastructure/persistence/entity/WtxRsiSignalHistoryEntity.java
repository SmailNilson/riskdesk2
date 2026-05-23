package com.riskdesk.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "wtxrsi_signal_history",
        indexes = {
                @Index(name = "ix_wtxrsi_sig_instr_tf_ts",
                        columnList = "instrument,timeframe,signalTs DESC")
        }
)
public class WtxRsiSignalHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String instrument;

    @Column(nullable = false, length = 10)
    private String timeframe;

    @Column(nullable = false)
    private Instant signalTs;

    @Column(nullable = false, length = 10)
    private String side;       // LONG | SHORT

    @Column(nullable = false, length = 20)
    private String action;     // OPEN_LONG | OPEN_SHORT | CLOSE_LONG | CLOSE_SHORT | NONE

    @Column(precision = 20, scale = 8)
    private BigDecimal wt1;

    @Column(precision = 20, scale = 8)
    private BigDecimal wt2;

    @Column(precision = 20, scale = 8)
    private BigDecimal rsi;

    @Column(precision = 20, scale = 8)
    private BigDecimal rsiSma;

    @Column(precision = 20, scale = 8)
    private BigDecimal chaikin;

    @Column
    private Boolean chaikinConfirmed;

    @Column(precision = 20, scale = 8)
    private BigDecimal entryPrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal stopLoss;

    @Column(precision = 20, scale = 8)
    private BigDecimal takeProfit;

    @Column
    private Integer contracts;

    @Column(length = 40)
    private String routingOutcome;

    @Column(length = 300)
    private String routingErrorMessage;

    public WtxRsiSignalHistoryEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getInstrument() { return instrument; }
    public void setInstrument(String instrument) { this.instrument = instrument; }
    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }
    public Instant getSignalTs() { return signalTs; }
    public void setSignalTs(Instant signalTs) { this.signalTs = signalTs; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public BigDecimal getWt1() { return wt1; }
    public void setWt1(BigDecimal wt1) { this.wt1 = wt1; }
    public BigDecimal getWt2() { return wt2; }
    public void setWt2(BigDecimal wt2) { this.wt2 = wt2; }
    public BigDecimal getRsi() { return rsi; }
    public void setRsi(BigDecimal rsi) { this.rsi = rsi; }
    public BigDecimal getRsiSma() { return rsiSma; }
    public void setRsiSma(BigDecimal rsiSma) { this.rsiSma = rsiSma; }
    public BigDecimal getChaikin() { return chaikin; }
    public void setChaikin(BigDecimal chaikin) { this.chaikin = chaikin; }
    public Boolean getChaikinConfirmed() { return chaikinConfirmed; }
    public void setChaikinConfirmed(Boolean chaikinConfirmed) { this.chaikinConfirmed = chaikinConfirmed; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
    public BigDecimal getStopLoss() { return stopLoss; }
    public void setStopLoss(BigDecimal stopLoss) { this.stopLoss = stopLoss; }
    public BigDecimal getTakeProfit() { return takeProfit; }
    public void setTakeProfit(BigDecimal takeProfit) { this.takeProfit = takeProfit; }
    public Integer getContracts() { return contracts; }
    public void setContracts(Integer contracts) { this.contracts = contracts; }
    public String getRoutingOutcome() { return routingOutcome; }
    public void setRoutingOutcome(String routingOutcome) { this.routingOutcome = routingOutcome; }
    public String getRoutingErrorMessage() { return routingErrorMessage; }
    public void setRoutingErrorMessage(String routingErrorMessage) { this.routingErrorMessage = routingErrorMessage; }
}
