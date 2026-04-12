package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.DepthSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Repository
public interface JpaDepthSnapshotRepository extends JpaRepository<DepthSnapshotEntity, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM DepthSnapshotEntity d WHERE d.timestamp < :cutoff")
    int deleteByTimestampBefore(Instant cutoff);
}
