package com.riskdesk.application.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring-Boot binding for {@code riskdesk.playbook.position-watch.*}.
 *
 * <p>Controls {@link PlaybookPositionReconciler} — the app-side enforcement of the VIRTUAL
 * stop-loss / take-profit on <em>filled</em> live {@code PLAYBOOK_AUTO} positions. The Playbook
 * live path ({@code PlaybookAutomationService.routeLive}) submits ONLY the entry order to IBKR and
 * stores the SL/TP as virtual levels; nothing at the broker enforces them once the entry fills. The
 * {@link com.riskdesk.application.quant.positions.VirtualStopWatcher} that <em>does</em> act on those
 * levels is scoped to {@code MANUAL_QUANT_PANEL} rows only, so a filled Playbook position used to run
 * unprotected until the operator closed it by hand. This reconciler closes that gap.</p>
 *
 * <p>Default ENABLED: a live automated strategy whose SL/TP are part of its spec must have them
 * enforced. This is APP-SIDE protection only — it never places a broker bracket order, so it does not
 * survive a backend outage / disconnect (that residual gap is what real OCO brackets would close,
 * deferred). The {@code enabled} flag is read on every tick so it can be flipped as a kill-switch
 * without a restart.</p>
 */
@ConfigurationProperties(prefix = "riskdesk.playbook.position-watch")
public class PlaybookPositionWatchProperties {

    /** Master switch. When false the reconciler runs but never auto-closes a Playbook position. */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
