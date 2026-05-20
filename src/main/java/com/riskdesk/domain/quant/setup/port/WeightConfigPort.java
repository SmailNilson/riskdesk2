package com.riskdesk.domain.quant.setup.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.setup.WeightConfiguration;

/** Output port for loading and persisting per-instrument weight configurations. */
public interface WeightConfigPort {

    WeightConfiguration load(Instrument instrument);

    void save(Instrument instrument, WeightConfiguration config);
}
