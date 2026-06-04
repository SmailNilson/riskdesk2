package com.riskdesk.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Singleton row holding the runtime marketable-execution policy (global — one row for the whole execution
 * core). The fixed {@code id} ({@link #SINGLETON_ID}) makes it a singleton under Hibernate DDL. Persisted so
 * an operator's UI change survives a restart.
 */
@Entity
@Table(name = "execution_marketable_settings")
public class MarketableExecutionSettingsEntity {

    public static final String SINGLETON_ID = "GLOBAL";

    @Id
    @Column(nullable = false, length = 16)
    private String id;

    @Column(name = "close_enabled", nullable = false)
    private boolean closeEnabled;

    @Column(name = "reverse_open_enabled", nullable = false)
    private boolean reverseOpenEnabled;

    @Column(name = "cross_ticks", nullable = false)
    private int crossTicks;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public boolean isCloseEnabled() { return closeEnabled; }
    public void setCloseEnabled(boolean closeEnabled) { this.closeEnabled = closeEnabled; }

    public boolean isReverseOpenEnabled() { return reverseOpenEnabled; }
    public void setReverseOpenEnabled(boolean reverseOpenEnabled) { this.reverseOpenEnabled = reverseOpenEnabled; }

    public int getCrossTicks() { return crossTicks; }
    public void setCrossTicks(int crossTicks) { this.crossTicks = crossTicks; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
