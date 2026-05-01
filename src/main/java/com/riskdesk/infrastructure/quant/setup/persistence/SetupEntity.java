package com.riskdesk.infrastructure.quant.setup.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code setups} table.
 * Lists (gateResults) are serialised as JSON in a TEXT column.
 */
@Entity
@Table(name = "setups")
public class SetupEntity {

    @Id
    @Column(name = "setup_id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "instrument", nullable = false, length = 20)
    private String instrument;

    @Column(name = "template", nullable = false, length = 30)
    private String template;

    @Column(name = "style", nullable = false, length = 10)
    private String style;

    @Column(name = "phase", nullable = false, length = 20)
    private String phase;

    @Column(name = "regime", nullable = false, length = 20)
    private String regime;

    @Column(name = "direction", nullable = false, length = 10)
    private String direction;

    @Column(name = "final_score")
    private double finalScore;

    @Column(name = "entry_price", precision = 18, scale = 4)
    private BigDecimal entryPrice;

    @Column(name = "sl_price", precision = 18, scale = 4)
    private BigDecimal slPrice;

    @Column(name = "tp1_price", precision = 18, scale = 4)
    private BigDecimal tp1Price;

    @Column(name = "tp2_price", precision = 18, scale = 4)
    private BigDecimal tp2Price;

    @Column(name = "rr_ratio")
    private double rrRatio;

    @Column(name = "playbook_id", length = 40)
    private String playbookId;

    @Lob
    @Column(name = "gate_results_json", columnDefinition = "text")
    private String gateResultsJson;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SetupEntity() {}

    public SetupEntity(UUID id) {
        this.id = id;
    }

    // ── Getters & setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }

    public String getInstrument() { return instrument; }
    public void setInstrument(String instrument) { this.instrument = instrument; }

    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }

    public String getStyle() { return style; }
    public void setStyle(String style) { this.style = style; }

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }

    public String getRegime() { return regime; }
    public void setRegime(String regime) { this.regime = regime; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public double getFinalScore() { return finalScore; }
    public void setFinalScore(double finalScore) { this.finalScore = finalScore; }

    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }

    public BigDecimal getSlPrice() { return slPrice; }
    public void setSlPrice(BigDecimal slPrice) { this.slPrice = slPrice; }

    public BigDecimal getTp1Price() { return tp1Price; }
    public void setTp1Price(BigDecimal tp1Price) { this.tp1Price = tp1Price; }

    public BigDecimal getTp2Price() { return tp2Price; }
    public void setTp2Price(BigDecimal tp2Price) { this.tp2Price = tp2Price; }

    public double getRrRatio() { return rrRatio; }
    public void setRrRatio(double rrRatio) { this.rrRatio = rrRatio; }

    public String getPlaybookId() { return playbookId; }
    public void setPlaybookId(String playbookId) { this.playbookId = playbookId; }

    public String getGateResultsJson() { return gateResultsJson; }
    public void setGateResultsJson(String gateResultsJson) { this.gateResultsJson = gateResultsJson; }

    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
