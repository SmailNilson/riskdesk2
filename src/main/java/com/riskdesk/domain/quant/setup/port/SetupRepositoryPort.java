package com.riskdesk.domain.quant.setup.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.setup.SetupPhase;
import com.riskdesk.domain.quant.setup.SetupRecommendation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Output port for persisting and querying {@link SetupRecommendation}s. */
public interface SetupRepositoryPort {

    void save(SetupRecommendation recommendation);

    Optional<SetupRecommendation> findById(UUID id);

    List<SetupRecommendation> findActiveByInstrument(Instrument instrument);

    void updatePhase(UUID id, SetupPhase phase, Instant updatedAt);
}
