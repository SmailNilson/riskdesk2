package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.contract.port.ActiveContractSnapshotStore;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.ActiveContractSnapshotEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * JPA adapter for {@link ActiveContractSnapshotStore}.
 * Converts between the domain {@code Snapshot} record and {@link ActiveContractSnapshotEntity}.
 */
@Component
public class JpaActiveContractSnapshotAdapter implements ActiveContractSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(JpaActiveContractSnapshotAdapter.class);

    private final JpaActiveContractSnapshotRepository repository;

    public JpaActiveContractSnapshotAdapter(JpaActiveContractSnapshotRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Snapshot> load(Instrument instrument) {
        return repository.findById(instrument).map(this::toDomain);
    }

    @Override
    @Transactional
    public void save(Instrument instrument, String contractMonth, Source source, Instant resolvedAt) {
        ActiveContractSnapshotEntity entity = repository.findById(instrument)
            .orElseGet(() -> new ActiveContractSnapshotEntity(instrument, contractMonth, source.name(), resolvedAt));
        entity.setContractMonth(contractMonth);
        entity.setSource(source.name());
        entity.setResolvedAt(resolvedAt);
        repository.save(entity);
    }

    private Snapshot toDomain(ActiveContractSnapshotEntity e) {
        Source src;
        try {
            src = Source.valueOf(e.getSource());
        } catch (IllegalArgumentException ex) {
            // Tolerate an unknown persisted source (older row, renamed enum, etc.).
            // The month is still usable; provenance is just less precise.
            log.warn("ActiveContractSnapshot: unknown source '{}' for {} — treating as IBKR_FRONT",
                e.getSource(), e.getInstrument());
            src = Source.IBKR_FRONT;
        }
        return new Snapshot(e.getInstrument(), e.getContractMonth(), src, e.getResolvedAt());
    }
}
