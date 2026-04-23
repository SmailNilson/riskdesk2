package com.riskdesk.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * PR-7 · Persists the "last fired candle" guard per signal so that after a
 * restart, the evaluator cannot re-fire an alert on the same candle that
 * already triggered a Mentor review before the restart.
 *
 * <p>Conceptually distinct from {@link AlertEvaluatorStateEntity}: the state
 * entity tracks <em>what signal</em> was last emitted; this entity tracks
 * <em>which candle</em> was last admitted. Both must survive a restart to
 * avoid wasted Gemini API calls or duplicate alerts on recompute.
 */
@Entity
@Table(name = "alert_evaluator_candle_guard", indexes = {
    @Index(name = "idx_alert_candle_guard_updated_at", columnList = "updatedAt")
})
public class AlertEvaluatorCandleGuardEntity {

    @Id
    @Column(length = 255)
    private String evalKey;

    @Column(nullable = false)
    private Instant candleTimestamp;

    @Column(nullable = false)
    private Instant updatedAt;

    public AlertEvaluatorCandleGuardEntity() {}

    public AlertEvaluatorCandleGuardEntity(String evalKey, Instant candleTimestamp, Instant updatedAt) {
        this.evalKey = evalKey;
        this.candleTimestamp = candleTimestamp;
        this.updatedAt = updatedAt;
    }

    public String getEvalKey() { return evalKey; }
    public void setEvalKey(String evalKey) { this.evalKey = evalKey; }

    public Instant getCandleTimestamp() { return candleTimestamp; }
    public void setCandleTimestamp(Instant candleTimestamp) { this.candleTimestamp = candleTimestamp; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
