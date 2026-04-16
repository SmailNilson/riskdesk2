package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.ActiveContractEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActiveContractJpaRepository extends JpaRepository<ActiveContractEntity, String> {
}
