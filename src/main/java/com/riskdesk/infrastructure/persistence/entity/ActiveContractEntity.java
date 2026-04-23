package com.riskdesk.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "active_contract", indexes = {
    @Index(name = "idx_active_contract_updated_at", columnList = "updatedAt")
})
public class ActiveContractEntity {

    @Id
    @Column(length = 16)
    private String instrument;

    @Column(nullable = false, length = 8)
    private String contractMonth;

    @Column
    private Integer conId;

    @Column(nullable = false)
    private Instant updatedAt;

    public ActiveContractEntity() {}

    public ActiveContractEntity(String instrument, String contractMonth, Integer conId, Instant updatedAt) {
        this.instrument = instrument;
        this.contractMonth = contractMonth;
        this.conId = conId;
        this.updatedAt = updatedAt;
    }

    public String getInstrument() { return instrument; }
    public void setInstrument(String instrument) { this.instrument = instrument; }

    public String getContractMonth() { return contractMonth; }
    public void setContractMonth(String contractMonth) { this.contractMonth = contractMonth; }

    public Integer getConId() { return conId; }
    public void setConId(Integer conId) { this.conId = conId; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
