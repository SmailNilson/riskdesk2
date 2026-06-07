package com.riskdesk.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Per-(instrument, timeframe) WTX parameter overrides (WaveTrend periods + initial-stop ATR multiple).
 * Brand-new table → Hibernate {@code ddl-auto=update} creates it cleanly (no NOT-NULL-add migration needed).
 * All override columns are nullable: null = "use the global config value".
 */
@Entity
@Table(name = "wtx_param_overrides")
@IdClass(WtxParamOverrideId.class)
public class WtxParamOverrideEntity {

    @Id
    @Column(nullable = false, length = 20)
    private String instrument;

    @Id
    @Column(nullable = false, length = 10)
    private String timeframe;

    @Column
    private Integer n1;

    @Column
    private Integer n2;

    @Column
    private Integer signalPeriod;

    @Column(precision = 10, scale = 4)
    private BigDecimal slAtrMult;

    @Column
    private Instant updatedAt;

    public WtxParamOverrideEntity() {}

    public String getInstrument() { return instrument; }
    public void setInstrument(String instrument) { this.instrument = instrument; }

    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }

    public Integer getN1() { return n1; }
    public void setN1(Integer n1) { this.n1 = n1; }

    public Integer getN2() { return n2; }
    public void setN2(Integer n2) { this.n2 = n2; }

    public Integer getSignalPeriod() { return signalPeriod; }
    public void setSignalPeriod(Integer signalPeriod) { this.signalPeriod = signalPeriod; }

    public BigDecimal getSlAtrMult() { return slAtrMult; }
    public void setSlAtrMult(BigDecimal slAtrMult) { this.slAtrMult = slAtrMult; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
