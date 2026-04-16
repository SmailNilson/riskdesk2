package com.riskdesk.infrastructure.persistence.entity;

import com.riskdesk.domain.model.Instrument;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the last-known-good active contract month per instrument.
 *
 * <p>Primary key is the {@link Instrument} itself — at most one row per instrument.
 * Populated by successful IBKR resolutions and by confirmed rollovers.
 */
@Entity
@Table(name = "active_contract_snapshot")
public class ActiveContractSnapshotEntity {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Instrument instrument;

    /** YYYYMM, e.g. "202606". */
    @Column(name = "contract_month", nullable = false, length = 6)
    private String contractMonth;

    /** Persisted name of {@code ActiveContractSnapshotStore.Source}. */
    @Column(nullable = false, length = 32)
    private String source;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt;

    protected ActiveContractSnapshotEntity() {}

    public ActiveContractSnapshotEntity(Instrument instrument, String contractMonth,
                                        String source, Instant resolvedAt) {
        this.instrument    = instrument;
        this.contractMonth = contractMonth;
        this.source        = source;
        this.resolvedAt    = resolvedAt;
    }

    public Instrument getInstrument() { return instrument; }

    public String getContractMonth() { return contractMonth; }
    public void setContractMonth(String contractMonth) { this.contractMonth = contractMonth; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
