package com.riskdesk.infrastructure.quant.setup.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SetupJpaRepository extends JpaRepository<SetupEntity, UUID> {

    List<SetupEntity> findByInstrumentAndPhaseNotIn(String instrument, List<String> excludedPhases);
}
