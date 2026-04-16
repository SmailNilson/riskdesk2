package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.ActiveContractSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaActiveContractSnapshotRepository
        extends JpaRepository<ActiveContractSnapshotEntity, Instrument> {
}
