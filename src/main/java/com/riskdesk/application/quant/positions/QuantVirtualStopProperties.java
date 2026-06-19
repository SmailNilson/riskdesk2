package com.riskdesk.application.quant.positions;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring-Boot binding for {@code riskdesk.quant.virtual-stop.*}.
 *
 * <p>Controls {@link VirtualStopWatcher} — the app-side auto-exit for MANUAL chart trades whose
 * virtual SL/TP get crossed. Default DISABLED: the watcher bean still runs but early-returns on every
 * tick until the operator opts in. This is an APP-SIDE protection only — it never places broker
 * bracket orders, so it does not survive a backend outage (that gap is what real OCO brackets would
 * close). The {@code enabled} flag is read on every tick so it can be toggled at runtime.</p>
 */
@ConfigurationProperties(prefix = "riskdesk.quant.virtual-stop")
public class QuantVirtualStopProperties {

    /** Master switch. When false the watcher runs but never auto-closes a position. */
    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
