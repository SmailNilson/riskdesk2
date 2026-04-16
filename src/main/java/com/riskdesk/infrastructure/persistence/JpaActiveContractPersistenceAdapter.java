package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.contract.port.ActiveContractPersistencePort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.ActiveContractEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

@Component
public class JpaActiveContractPersistenceAdapter implements ActiveContractPersistencePort {

    private static final Logger log = LoggerFactory.getLogger(JpaActiveContractPersistenceAdapter.class);

    private final ActiveContractJpaRepository repository;

    public JpaActiveContractPersistenceAdapter(ActiveContractJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<String> load(Instrument instrument) {
        return repository.findById(instrument.name())
            .map(ActiveContractEntity::getContractMonth);
    }

    @Override
    public Map<Instrument, String> loadAll() {
        Map<Instrument, String> result = new EnumMap<>(Instrument.class);
        for (ActiveContractEntity entity : repository.findAll()) {
            if (entity == null || entity.getInstrument() == null || entity.getContractMonth() == null) {
                continue;
            }
            Instrument instrument;
            try {
                instrument = Instrument.valueOf(entity.getInstrument());
            } catch (IllegalArgumentException ex) {
                log.warn("Skipping unknown instrument '{}' in active_contract table", entity.getInstrument());
                continue;
            }
            result.put(instrument, entity.getContractMonth());
        }
        return result;
    }

    @Override
    public void save(Instrument instrument, String contractMonth) {
        // instrument.name() is the @Id — JPA merge handles insert-or-update without a prior SELECT
        repository.save(new ActiveContractEntity(instrument.name(), contractMonth, null, Instant.now()));
    }
}
