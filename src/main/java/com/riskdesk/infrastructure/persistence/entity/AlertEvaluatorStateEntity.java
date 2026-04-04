package com.riskdesk.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "alert_evaluator_state")
public class AlertEvaluatorStateEntity {

    @Id
    @Column(length = 255)
    private String evalKey;

    @Column(nullable = false, length = 128)
    private String signal;

    @Column(nullable = false)
    private Instant updatedAt;

    public AlertEvaluatorStateEntity() {}

    public AlertEvaluatorStateEntity(String evalKey, String signal, Instant updatedAt) {
        this.evalKey = evalKey;
        this.signal = signal;
        this.updatedAt = updatedAt;
    }

    public String getEvalKey() { return evalKey; }
    public void setEvalKey(String evalKey) { this.evalKey = evalKey; }

    public String getSignal() { return signal; }
    public void setSignal(String signal) { this.signal = signal; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
