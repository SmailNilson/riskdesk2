package com.riskdesk.infrastructure.quant;

import com.riskdesk.domain.quant.engine.QuantGateConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring-Boot binding for the {@code riskdesk.quant.veto.*} and
 * {@code riskdesk.quant.gates.*} keys — the externalized calibration of the
 * {@link com.riskdesk.domain.quant.engine.GateEvaluator}. The domain never
 * reads configuration directly: {@code QuantConfiguration} converts this
 * binding into the framework-free {@link QuantGateConfig} value object.
 *
 * <p>Defaults mirror {@link QuantGateConfig#defaults()} and the 2026-06
 * event-study recalibration (veto base 50 → 60 + 10-min linear decay;
 * per-instrument G3/L3 delta magnitude).</p>
 */
@Component
@ConfigurationProperties(prefix = "riskdesk.quant")
public class QuantGateProperties {

    private Veto veto = new Veto();
    private Gates gates = new Gates();

    public Veto getVeto() { return veto; }
    public void setVeto(Veto veto) { this.veto = veto; }
    public Gates getGates() { return gates; }
    public void setGates(Gates gates) { this.gates = gates; }

    /** Converts the Spring binding into the domain value object. */
    public QuantGateConfig toDomainConfig() {
        return new QuantGateConfig(
            veto.getBaseThreshold(),
            veto.getDecaySeconds(),
            gates.getDeltaThreshold(),
            gates.getDefaultDeltaThreshold(),
            gates.getBearishBuyPct(),
            gates.getBullishBuyPct()
        );
    }

    /** A/D structural veto (G5/L5) calibration. */
    public static class Veto {
        /**
         * Base tier of the dynamic veto threshold. Raised 50 → 60: the old
         * detector confidence floor (50) equalled the old base tier, so every
         * DISTRIBUTION/ACCUMULATION event vetoed by construction. Escalation
         * tiers are derived as base+10 / base+20 to stay monotonic.
         */
        private double baseThreshold = QuantGateConfig.DEFAULT_VETO_BASE_THRESHOLD;
        /**
         * Linear age-decay horizon of the veto: effective confidence
         * = conf × max(0, 1 - age/decaySeconds). 600 s matches the 10-min
         * application-layer lookup window so a veto can actually expire.
         */
        private long decaySeconds = QuantGateConfig.DEFAULT_VETO_DECAY_SECONDS;

        public double getBaseThreshold() { return baseThreshold; }
        public void setBaseThreshold(double v) { this.baseThreshold = v; }
        public long getDecaySeconds() { return decaySeconds; }
        public void setDecaySeconds(long v) { this.decaySeconds = v; }
    }

    /** G3/G4 + L3/L4 gate bands. */
    public static class Gates {
        /**
         * Per-instrument G3/L3 delta gate magnitude (±). The historical ±100
         * constant came from an MNQ-specific monitor; MCL trades far less so
         * ±100 left its gate nearly always red.
         */
        private Map<String, Double> deltaThreshold = new HashMap<>(Map.of(
            "MNQ", 100.0,
            "MCL", 40.0
        ));
        /** Fallback for instruments missing from {@link #deltaThreshold}. */
        private double defaultDeltaThreshold = QuantGateConfig.DEFAULT_DELTA_THRESHOLD;
        /** G4 — buy% below this favours SHORT. */
        private double bearishBuyPct = QuantGateConfig.DEFAULT_BEARISH_BUY_PCT;
        /** L4 — buy% above this favours LONG. */
        private double bullishBuyPct = QuantGateConfig.DEFAULT_BULLISH_BUY_PCT;

        public Map<String, Double> getDeltaThreshold() { return deltaThreshold; }
        public void setDeltaThreshold(Map<String, Double> v) { this.deltaThreshold = v; }
        public double getDefaultDeltaThreshold() { return defaultDeltaThreshold; }
        public void setDefaultDeltaThreshold(double v) { this.defaultDeltaThreshold = v; }
        public double getBearishBuyPct() { return bearishBuyPct; }
        public void setBearishBuyPct(double v) { this.bearishBuyPct = v; }
        public double getBullishBuyPct() { return bullishBuyPct; }
        public void setBullishBuyPct(double v) { this.bullishBuyPct = v; }
    }
}
