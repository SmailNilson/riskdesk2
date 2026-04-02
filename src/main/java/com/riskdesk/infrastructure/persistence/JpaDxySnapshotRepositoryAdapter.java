package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.marketdata.model.DxySnapshot;
import com.riskdesk.domain.marketdata.port.DxySnapshotRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class JpaDxySnapshotRepositoryAdapter implements DxySnapshotRepositoryPort {

    private final MarketDxySnapshotJpaRepository repository;

    public JpaDxySnapshotRepositoryAdapter(MarketDxySnapshotJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public DxySnapshot save(DxySnapshot snapshot) {
        return MarketDxySnapshotEntityMapper.toDomain(
            repository.save(MarketDxySnapshotEntityMapper.toEntity(snapshot))
        );
    }

    @Override
    public Optional<DxySnapshot> findLatestComplete() {
        return repository.findTopByCompleteTrueOrderByTimestampDesc()
            .map(MarketDxySnapshotEntityMapper::toDomain);
    }

    @Override
    public Optional<DxySnapshot> findLatestCompleteAtOrBefore(Instant cutoff) {
        return repository.findTopByCompleteTrueAndTimestampLessThanEqualOrderByTimestampDesc(cutoff)
            .map(MarketDxySnapshotEntityMapper::toDomain);
    }

    @Override
    public List<DxySnapshot> findCompleteBetween(Instant from, Instant to) {
        return repository.findByCompleteTrueAndTimestampBetweenOrderByTimestampAsc(from, to).stream()
            .map(MarketDxySnapshotEntityMapper::toDomain)
            .toList();
    }
}
