package com.riskdesk.infrastructure.quant.setup.config;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.setup.WeightConfiguration;
import com.riskdesk.domain.quant.setup.port.WeightConfigPort;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * In-memory {@link WeightConfigPort}. Returns {@link WeightConfiguration#DEFAULT}
 * for all instruments until explicitly overridden via the REST API.
 * Replaced by a JPA-backed adapter in a future slice when the weight optimizer
 * writes learned weights to the DB.
 */
@Component
public class InMemoryWeightConfigAdapter implements WeightConfigPort {

    private final Map<Instrument, WeightConfiguration> store = new EnumMap<>(Instrument.class);

    @Override
    public WeightConfiguration load(Instrument instrument) {
        return store.getOrDefault(instrument, WeightConfiguration.DEFAULT);
    }

    @Override
    public void save(Instrument instrument, WeightConfiguration config) {
        store.put(instrument, config.normalised());
    }
}
