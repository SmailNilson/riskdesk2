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
        // READ path: a transient load failure serves the configured defaults WITHOUT caching them (so a later
        // read retries and a persisted value is picked up once the DB recovers). Best-effort — reads tolerate
        // a brief defaults window; the WRITE path does NOT (see update()).
        try {
            return loadIntoCache();
        } catch (RuntimeException e) {
            log.warn("Marketable settings load failed ({}) — serving configured defaults, will retry", e.toString());
            return defaults;
        }
    }

    /** Partial update (null fields keep their current PERSISTED value) → validate → persist → refresh cache. */
    public synchronized MarketableExecutionSettings update(Boolean closeEnabled,
                                                           Boolean reverseOpenEnabled,
                                                           Integer crossTicks) {
        if (crossTicks != null && (crossTicks < 0 || crossTicks > MAX_CROSS_TICKS)) {
            throw new IllegalArgumentException("crossTicks must be between 0 and " + MAX_CROSS_TICKS);
        }
        // Merge omitted fields from the AUTHORITATIVE current value (cache, or a successful load) — NEVER from
        // fallback defaults. If the load fails transiently, loadIntoCache() PROPAGATES, so the PUT fails and the
        // operator retries, rather than clobbering a persisted policy (e.g. toggling only closeEnabled must not
        // save reverseOpen=true/cross=10 over a persisted reverseOpen=false/cross=4).
        MarketableExecutionSettings cur = loadIntoCache();
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

    /**
     * The authoritative current settings: the cache if populated, else a successful load (a persisted value, or
     * a confirmed-absent row → defaults). Caches a successful load. PROPAGATES a transient load failure so the
     * write path ({@link #update}) fails instead of composing from fallback defaults; the read path
     * ({@link #current}) catches it and serves defaults.
     */
    private MarketableExecutionSettings loadIntoCache() {
        MarketableExecutionSettings c = cached;
        if (c != null) {
            return c;
        }
        MarketableExecutionSettings loaded = store.load().orElse(defaults);
        cached = loaded;
        return loaded;
    }
}
