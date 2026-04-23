package com.riskdesk.infrastructure.persistence.entity;

import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.domain.simulation.ReviewType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity for the {@code trade_simulations} table — Phase 1a foundation for
 * the Simulation Decoupling Rule. See
 * {@link com.riskdesk.domain.simulation.TradeSimulation}.
 */
@Entity
@Table(
    name = "trade_simulations",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_trade_simulations_review",
            columnNames = {"review_id", "review_type"}
        )
    },
    indexes = {
        @Index(name = "idx_trade_simulations_status", columnList = "simulation_status"),
        @Index(name = "idx_trade_simulations_instrument_created_at",
               columnList = "instrument, created_at"),
        @Index(name = "idx_trade_simulations_created_at", columnList = "created_at")
    }
)
public class TradeSimulationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false)
    private long reviewId;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_type", nullable = false, length = 16)
    private ReviewType reviewType;

    @Column(name = "instrument", nullable = false, length = 16)
    private String instrument;

    @Column(name = "action", nullable = false, length = 8)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "simulation_status", nullable = false, length = 32)
    private TradeSimulationStatus simulationStatus;

    @Column(name = "activation_time", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private Instant activationTime;

    @Column(name = "resolution_time", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private Instant resolutionTime;

    @Column(name = "max_drawdown_points", precision = 19, scale = 6)
    private BigDecimal maxDrawdownPoints;

    @Column(name = "trailing_stop_result", length = 32)
    private String trailingStopResult;

    @Column(name = "trailing_exit_price", precision = 19, scale = 6)
    private BigDecimal trailingExitPrice;

    @Column(name = "best_favorable_price", precision = 19, scale = 6)
    private BigDecimal bestFavorablePrice;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getReviewId() {
        return reviewId;
    }

    public void setReviewId(long reviewId) {
        this.reviewId = reviewId;
    }

    public ReviewType getReviewType() {
        return reviewType;
    }

    public void setReviewType(ReviewType reviewType) {
        this.reviewType = reviewType;
    }

    public String getInstrument() {
        return instrument;
    }

    public void setInstrument(String instrument) {
        this.instrument = instrument;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public TradeSimulationStatus getSimulationStatus() {
        return simulationStatus;
    }

    public void setSimulationStatus(TradeSimulationStatus simulationStatus) {
        this.simulationStatus = simulationStatus;
    }

    public Instant getActivationTime() {
        return activationTime;
    }

    public void setActivationTime(Instant activationTime) {
        this.activationTime = activationTime;
    }

    public Instant getResolutionTime() {
        return resolutionTime;
    }

    public void setResolutionTime(Instant resolutionTime) {
        this.resolutionTime = resolutionTime;
    }

    public BigDecimal getMaxDrawdownPoints() {
        return maxDrawdownPoints;
    }

    public void setMaxDrawdownPoints(BigDecimal maxDrawdownPoints) {
        this.maxDrawdownPoints = maxDrawdownPoints;
    }

    public String getTrailingStopResult() {
        return trailingStopResult;
    }

    public void setTrailingStopResult(String trailingStopResult) {
        this.trailingStopResult = trailingStopResult;
    }

    public BigDecimal getTrailingExitPrice() {
        return trailingExitPrice;
    }

    public void setTrailingExitPrice(BigDecimal trailingExitPrice) {
        this.trailingExitPrice = trailingExitPrice;
    }

    public BigDecimal getBestFavorablePrice() {
        return bestFavorablePrice;
    }

    public void setBestFavorablePrice(BigDecimal bestFavorablePrice) {
        this.bestFavorablePrice = bestFavorablePrice;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
