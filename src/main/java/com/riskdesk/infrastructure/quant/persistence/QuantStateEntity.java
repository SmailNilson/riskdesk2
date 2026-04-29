package com.riskdesk.infrastructure.quant.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * Persists the per-instrument {@code QuantState} between scheduler ticks.
 * <p>
 * The history lists are stored as plain JSON in TEXT columns to avoid coupling
 * the domain to a relational shape — each scan rewrites the whole row
 * (idempotent upsert, < 1 KB payload).
 */
@Entity
@Table(name = "quant_state")
public class QuantStateEntity {

    @Id
    @Column(name = "instrument", length = 10, nullable = false)
    private String instrument;

    @Column(name = "session_date")
    private LocalDate sessionDate;

    @Column(name = "monitor_start_px")
    private Double monitorStartPx;

    /** JSON array of doubles, e.g. {@code [-100.5, -250.0, -310.5]}. */
    @Lob
    @Column(name = "delta_history_json", columnDefinition = "text")
    private String deltaHistoryJson;

    /** JSON array of {@code {"type":"DIST","conf":80.0,"ts":"..."}}. */
    @Lob
    @Column(name = "dist_only_history_json", columnDefinition = "text")
    private String distOnlyHistoryJson;

    @Lob
    @Column(name = "accu_only_history_json", columnDefinition = "text")
    private String accuOnlyHistoryJson;

    /** JSON array of ISO-8601 timestamps. */
    @Lob
    @Column(name = "abs_bull_scans_json", columnDefinition = "text")
    private String absBullScansJson;

    /** Persists publisher transition state across restarts (PR #297 follow-up review). */
    @Column(name = "last_signaled_score", nullable = false)
    private int lastSignaledScore = 0;

    protected QuantStateEntity() {}

    public QuantStateEntity(String instrument) {
        this.instrument = instrument;
    }

    public String getInstrument() { return instrument; }
    public void setInstrument(String instrument) { this.instrument = instrument; }
    public LocalDate getSessionDate() { return sessionDate; }
    public void setSessionDate(LocalDate sessionDate) { this.sessionDate = sessionDate; }
    public Double getMonitorStartPx() { return monitorStartPx; }
    public void setMonitorStartPx(Double monitorStartPx) { this.monitorStartPx = monitorStartPx; }
    public String getDeltaHistoryJson() { return deltaHistoryJson; }
    public void setDeltaHistoryJson(String deltaHistoryJson) { this.deltaHistoryJson = deltaHistoryJson; }
    public String getDistOnlyHistoryJson() { return distOnlyHistoryJson; }
    public void setDistOnlyHistoryJson(String s) { this.distOnlyHistoryJson = s; }
    public String getAccuOnlyHistoryJson() { return accuOnlyHistoryJson; }
    public void setAccuOnlyHistoryJson(String s) { this.accuOnlyHistoryJson = s; }
    public String getAbsBullScansJson() { return absBullScansJson; }
    public void setAbsBullScansJson(String s) { this.absBullScansJson = s; }
    public int getLastSignaledScore() { return lastSignaledScore; }
    public void setLastSignaledScore(int v) { this.lastSignaledScore = v; }
}
