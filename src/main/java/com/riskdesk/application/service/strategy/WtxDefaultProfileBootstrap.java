package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.engine.strategy.wtx.WtxProfile;
import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxStrategyStatePort;
import com.riskdesk.infrastructure.config.WtxStrategyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Ensures the configured instruments default to the HTF profile, persistently.
 *
 * <p>Backtests established HTF (1h trend-direction filter) as the only profile that makes WTX
 * viable — BASELINE has no edge and bleeds in trending regimes. So for each instrument in
 * {@code riskdesk.wtx.htf-default-instruments} × each configured timeframe, this upgrades any
 * (instrument, timeframe) state still on the {@code BASELINE} default to {@code HTF} once at boot.</p>
 *
 * <p>It only flips {@code BASELINE → HTF}: a state the operator manually set to SESSION_ATR or
 * STRICT is left untouched. Swing-bias stays at its default (OFF), which is the validated setting
 * alongside HTF (the two filters are redundant). Runs after the context is ready so the JPA
 * EntityManagerFactory and the schema migration have completed.</p>
 */
@Component
@ConditionalOnProperty(name = "riskdesk.wtx.enabled", havingValue = "true")
public class WtxDefaultProfileBootstrap {

    private static final Logger log = LoggerFactory.getLogger(WtxDefaultProfileBootstrap.class);

    private final WtxStrategyStatePort statePort;
    private final WtxStrategyProperties properties;

    public WtxDefaultProfileBootstrap(WtxStrategyStatePort statePort, WtxStrategyProperties properties) {
        this.statePort = statePort;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureHtfDefaults() {
        if (properties.getHtfDefaultInstruments() == null) return;
        for (String instrument : properties.getHtfDefaultInstruments()) {
            for (String timeframe : properties.getTimeframes()) {
                try {
                    WtxStrategyState state = statePort.load(instrument, timeframe)
                            .orElseGet(() -> WtxStrategyState.initial(instrument, timeframe,
                                    properties.getInitialEquity()));
                    WtxProfile current = state.activeProfile();
                    // Only upgrade the untouched BASELINE default — never stomp a manual choice.
                    if (current == null || current == WtxProfile.BASELINE) {
                        statePort.save(state.withProfile(WtxProfile.HTF));
                        log.info("WTX [{} {}] default profile set to HTF (was {})",
                                instrument, timeframe, current);
                    }
                } catch (Exception e) {
                    log.warn("WTX default-profile bootstrap failed for {} {}: {}",
                            instrument, timeframe, e.getMessage());
                }
            }
        }
    }
}
