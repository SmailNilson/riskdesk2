package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.SpoofingEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Repository
public interface JpaSpoofingEventRepository extends JpaRepository<SpoofingEventEntity, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM SpoofingEventEntity e WHERE e.timestamp < :cutoff")
    int deleteByTimestampBefore(Instant cutoff);
}
