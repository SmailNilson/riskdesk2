package com.riskdesk.application.quant.simulation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Spring-Boot binding for the {@code riskdesk.quant.sim-exec.*} keys — the
 * Auto-IBKR mirror for the Quant 7-Gates simulation harness.
 *
 * <p>Defaults here are deliberately safe: the master switch is OFF and the
 * allowlist is restricted to the two instruments that are net-positive in the
 * persisted simulation history (MNQ, MCL). MGC stays simulated-only (it is a
 * net loser) and 6E is never scanned by {@code QuantGateScheduler}, so neither
 * can route a live order even if a toggle were forced on.</p>
 */
@ConfigurationProperties(prefix = "riskdesk.quant.sim-exec")
public class QuantSimExecutionProperties {

    /**
     * Master switch. When false the {@link IbkrQuant7GatesExecutionBridge} bean
     * is not even built ({@code @ConditionalOnProperty}), so the simulation
     * never touches the broker — an extra safety latch on top of the
     * per-instrument toggles.
     */
    private boolean enabled = false;

    /**
     * Hard allowlist of instruments allowed to route a live order. A name not in
     * this list is rejected at the bridge regardless of its per-instrument
     * toggle. Defaults to the net-positive instruments only.
     */
    private List<String> instruments = List.of("MNQ", "MCL");

    /**
     * Broker account used when creating quant-sim executions. Required when
     * {@code enabled=true}; the bridge fails fast on startup if blank.
     */
    private String brokerAccountId = "";

    /** Default contract quantity per routed order. */
    private int defaultQuantity = 1;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getInstruments() { return instruments; }
    public void setInstruments(List<String> instruments) { this.instruments = instruments; }

    public String getBrokerAccountId() { return brokerAccountId; }
    public void setBrokerAccountId(String brokerAccountId) { this.brokerAccountId = brokerAccountId; }

    public int getDefaultQuantity() { return defaultQuantity; }
    public void setDefaultQuantity(int defaultQuantity) { this.defaultQuantity = defaultQuantity; }

    /** True when {@code instrument} (enum name) is on the execution allowlist. */
    public boolean isAllowed(String instrumentName) {
        return instrumentName != null && instruments != null && instruments.contains(instrumentName);
    }
}
