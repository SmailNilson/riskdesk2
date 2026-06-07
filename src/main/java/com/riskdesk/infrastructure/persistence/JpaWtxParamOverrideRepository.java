package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.WtxParamOverrideEntity;
import com.riskdesk.infrastructure.persistence.entity.WtxParamOverrideId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JpaWtxParamOverrideRepository
        extends JpaRepository<WtxParamOverrideEntity, WtxParamOverrideId> {

    Optional<WtxParamOverrideEntity> findByInstrumentAndTimeframe(String instrument, String timeframe);
}
