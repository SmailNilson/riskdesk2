package com.riskdesk.application.service;

import com.riskdesk.domain.execution.MarketableExecutionSettings;
import com.riskdesk.domain.execution.port.MarketableSettingsProvider;
import com.riskdesk.domain.execution.port.MarketableSettingsRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Owns the runtime marketable-execution policy: seeds from the configured {@code @Value} defaults, lets an
 * operator change it live (UI → REST), persists each change (survives restart), and serves the current value
 * cheaply to {@code DefaultOrderRouter} via {@link MarketableSettingsProvider} (cached, no DB hit per order).
 * Mirrors the Auto-IBKR toggle's load/validate/persist shape, but GLOBAL (one policy for the whole core).
 */
@Service
public class MarketableExecutionSettingsService implements MarketableSettingsProvider {

    private static final Logger log = LoggerFactory.getLogger(MarketableExecutionSettingsService.class);

    /** Sane upper bound on cross-ticks so a fat-finger can't arm an absurd slippage tolerance. */
    private static final int MAX_CROSS_TICKS = 1000;

    private final MarketableSettingsRepositoryPort store;
    private final MarketableExecutionSettings defaults;
    private volatile MarketableExecutionSettings cached;

    public MarketableExecutionSettingsService(
            MarketableSettingsRepositoryPort store,
            @Value("${riskdesk.execution.marketable-close.enabled:true}") boolean closeEnabled,
            @Value("${riskdesk.execution.marketable-reverse-open.enabled:true}") boolean reverseOpenEnabled,
            @Value("${riskdesk.execution.marketable-close.cross-ticks:10}") int crossTicks) {
        this.store = store;
        this.defaults = new MarketableExecutionSettings(closeEnabled, reverseOpenEnabled, crossTicks);
    }

    @Override
    public MarketableExecutionSettings current() {
        MarketableExecutionSettings c = cached;
        if (c == null) {
            c = loadOrDefault(); // lazy: persisted value if the operator ever saved, else the config defaults
            cached = c;
        }
        return c;
    }

    /** Partial update (null fields keep their current value) → validate → persist → refresh cache. */
    public synchronized MarketableExecutionSettings update(Boolean closeEnabled,
                                                           Boolean reverseOpenEnabled,
                                                           Integer crossTicks) {
        if (crossTicks != null && (crossTicks < 0 || crossTicks > MAX_CROSS_TICKS)) {
            throw new IllegalArgumentException("crossTicks must be between 0 and " + MAX_CROSS_TICKS);
        }
        MarketableExecutionSettings cur = current();
        MarketableExecutionSettings next = new MarketableExecutionSettings(
            closeEnabled != null ? closeEnabled : cur.closeEnabled(),
            reverseOpenEnabled != null ? reverseOpenEnabled : cur.reverseOpenEnabled(),
            crossTicks != null ? crossTicks : cur.crossTicks());
        store.save(next);
        cached = next;
        log.info("Marketable execution settings updated: close={} reverseOpen={} crossTicks={}",
            next.closeEnabled(), next.reverseOpenEnabled(), next.crossTicks());
        return next;
    }

    private MarketableExecutionSettings loadOrDefault() {
        try {
            return store.load().orElse(defaults);
        } catch (RuntimeException e) {
            log.warn("Marketable settings load failed ({}) — using configured defaults", e.toString());
            return defaults;
        }
    }
}
