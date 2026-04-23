package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.TickLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Repository
public interface JpaTickLogRepository extends JpaRepository<TickLogEntity, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM TickLogEntity t WHERE t.timestamp < :cutoff")
    int deleteByTimestampBefore(Instant cutoff);
}
