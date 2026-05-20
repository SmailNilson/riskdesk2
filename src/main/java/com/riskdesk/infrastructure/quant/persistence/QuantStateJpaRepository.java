package com.riskdesk.infrastructure.quant.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QuantStateJpaRepository extends JpaRepository<QuantStateEntity, String> {
}
