package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.MarketDxySnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MarketDxySnapshotJpaRepository extends JpaRepository<MarketDxySnapshotEntity, Long> {

    Optional<MarketDxySnapshotEntity> findTopByCompleteTrueOrderByTimestampDesc();

    Optional<MarketDxySnapshotEntity> findTopByCompleteTrueAndTimestampLessThanEqualOrderByTimestampDesc(Instant cutoff);

    List<MarketDxySnapshotEntity> findByCompleteTrueAndTimestampBetweenOrderByTimestampAsc(Instant from, Instant to);
}
