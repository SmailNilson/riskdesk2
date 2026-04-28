package com.riskdesk.infrastructure.externalsetup;

import com.riskdesk.domain.externalsetup.ExternalSetupStatus;
import com.riskdesk.domain.model.Instrument;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExternalSetupJpaRepository extends JpaRepository<ExternalSetupEntity, Long> {

    Optional<ExternalSetupEntity> findBySetupKey(String setupKey);

    @Query("""
        select e from ExternalSetupEntity e
         where e.status in :statuses
         order by e.submittedAt desc
        """)
    List<ExternalSetupEntity> findByStatuses(@Param("statuses") List<ExternalSetupStatus> statuses, Pageable pageable);

    @Query("""
        select e from ExternalSetupEntity e
         where e.status = com.riskdesk.domain.externalsetup.ExternalSetupStatus.PENDING
           and e.expiresAt <= :clock
         order by e.expiresAt asc
        """)
    List<ExternalSetupEntity> findPendingExpiredAt(@Param("clock") Instant clock, Pageable pageable);

    @Query("""
        select e from ExternalSetupEntity e
         where e.instrument = :instrument
           and e.submittedAt >= :since
         order by e.submittedAt desc
        """)
    List<ExternalSetupEntity> findRecentByInstrument(
        @Param("instrument") Instrument instrument,
        @Param("since") Instant since,
        Pageable pageable);
}
