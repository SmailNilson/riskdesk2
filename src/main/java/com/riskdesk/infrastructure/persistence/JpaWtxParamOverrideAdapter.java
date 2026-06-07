package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.engine.strategy.wtx.WtxParamOverride;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxParamOverridePort;
import com.riskdesk.infrastructure.persistence.entity.WtxParamOverrideEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JPA adapter for WTX param overrides. Reads are served from an in-memory cache (the override is
 * consulted on EVERY candle close, but changes only via the rare save() from the panel), so we avoid
 * a DB hit per bar. Single-instance deployment → the cache is the source of truth within the process.
 */
@Component
public class JpaWtxParamOverrideAdapter implements WtxParamOverridePort {

    private final JpaWtxParamOverrideRepository repository;
    private final ConcurrentHashMap<String, WtxParamOverride> cache = new ConcurrentHashMap<>();

    public JpaWtxParamOverrideAdapter(JpaWtxParamOverrideRepository repository) {
        this.repository = repository;
    }

    private static String key(String instrument, String timeframe) {
        return instrument + "|" + timeframe;
    }

    @Override
    public WtxParamOverride load(String instrument, String timeframe) {
        return cache.computeIfAbsent(key(instrument, timeframe), k ->
                repository.findByInstrumentAndTimeframe(instrument, timeframe)
                        .map(JpaWtxParamOverrideAdapter::toDomain)
                        .orElse(WtxParamOverride.NONE));
    }

    @Override
    public void save(String instrument, String timeframe, WtxParamOverride override) {
        WtxParamOverrideEntity entity = repository
                .findByInstrumentAndTimeframe(instrument, timeframe)
                .orElseGet(WtxParamOverrideEntity::new);
        entity.setInstrument(instrument);
        entity.setTimeframe(timeframe);
        entity.setN1(override.n1());
        entity.setN2(override.n2());
        entity.setSignalPeriod(override.signalPeriod());
        entity.setSlAtrMult(override.slAtrMult());
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        cache.put(key(instrument, timeframe), override == null ? WtxParamOverride.NONE : override);
    }

    private static WtxParamOverride toDomain(WtxParamOverrideEntity e) {
        return new WtxParamOverride(e.getN1(), e.getN2(), e.getSignalPeriod(), e.getSlAtrMult());
    }
}
