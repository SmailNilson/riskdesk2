package com.riskdesk.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * Spring configuration properties for the S4 execution gate.
 *
 * <p>The gate lets us introduce the probabilistic strategy engine into the
 * execution path <b>without</b> a big-bang cutover:
 *
 * <ul>
 *   <li>{@link #enabled} — master switch. When {@code false} (default) the gate is
 *       a no-op; the legacy execution path continues unchanged. Operators can flip
 *       this false → true without a restart by reloading configuration.</li>
 *   <li>{@link #instruments} — comma-separated list of instrument codes (e.g.
 *       {@code MGC,MCL}) enrolled in the gate. Instruments not in the list bypass
 *       the gate even when master {@link #enabled}=true. Empty = no instrument
 *       is enrolled.</li>
 * </ul>
 *
 * <p>Mode semantics for this slice: <b>VETO_ONLY</b>. The gate never creates
 * trades; it can only block trades that the legacy path would have approved.
 * A later slice can introduce a PRIMARY mode where the strategy engine alone
 * triggers executions.
 *
 * <p>Sample {@code application.properties}:
 * <pre>
 * riskdesk.strategy.execution-gate.enabled=true
 * riskdesk.strategy.execution-gate.instruments=MGC
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "riskdesk.strategy.execution-gate")
public class StrategyExecutionGateProperties {

    private boolean enabled = false;
    private Set<String> instruments = Collections.emptySet();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<String> getInstruments() {
        return instruments;
    }

    public void setInstruments(Set<String> instruments) {
        if (instruments == null) {
            this.instruments = Collections.emptySet();
            return;
        }
        // Normalise to upper-case so comparison matches Instrument enum names
        this.instruments = instruments.stream()
            .filter(s -> s != null && !s.isBlank())
            .map(s -> s.trim().toUpperCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** True when the gate should evaluate this specific instrument. */
    public boolean enrolls(String instrumentCode) {
        return enabled && instrumentCode != null
            && instruments.contains(instrumentCode.toUpperCase(Locale.ROOT));
    }
}
