package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.QuantScanSnapshotEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** Spring Data repository for the per-scan Quant flow log (90-day retention). */
@Repository
public interface JpaQuantScanSnapshotRepository extends JpaRepository<QuantScanSnapshotEntity, Long> {

    List<QuantScanSnapshotEntity> findByInstrumentAndScannedAtBetweenOrderByScannedAtDesc(
        Instrument instrument, Instant from, Instant to, Pageable pageable);

    @Modifying
    @Query("DELETE FROM QuantScanSnapshotEntity e WHERE e.scannedAt < :cutoff")
    int deleteByScannedAtBefore(@Param("cutoff") Instant cutoff);
}
