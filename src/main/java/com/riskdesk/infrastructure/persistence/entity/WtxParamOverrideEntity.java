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

    // Signal-zone gating overrides (nullable = use the global config). Added columns are
    // nullable so Hibernate ddl-auto=update extends the existing table in place.
    @Column(precision = 10, scale = 4)
    private BigDecimal nsc;

    @Column(precision = 10, scale = 4)
    private BigDecimal nsv;

    @Column
    private Boolean useCompra1;

    @Column
    private Boolean useVenta1;

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

    public BigDecimal getNsc() { return nsc; }
    public void setNsc(BigDecimal nsc) { this.nsc = nsc; }

    public BigDecimal getNsv() { return nsv; }
    public void setNsv(BigDecimal nsv) { this.nsv = nsv; }

    public Boolean getUseCompra1() { return useCompra1; }
    public void setUseCompra1(Boolean useCompra1) { this.useCompra1 = useCompra1; }

    public Boolean getUseVenta1() { return useVenta1; }
    public void setUseVenta1(Boolean useVenta1) { this.useVenta1 = useVenta1; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
