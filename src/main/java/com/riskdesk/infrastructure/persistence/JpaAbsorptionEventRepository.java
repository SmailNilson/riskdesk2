package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.AbsorptionEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Repository
public interface JpaAbsorptionEventRepository extends JpaRepository<AbsorptionEventEntity, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM AbsorptionEventEntity e WHERE e.timestamp < :cutoff")
    int deleteByTimestampBefore(Instant cutoff);
}
