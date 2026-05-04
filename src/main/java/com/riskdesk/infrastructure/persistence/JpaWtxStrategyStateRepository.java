package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.WtxStrategyStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaWtxStrategyStateRepository extends JpaRepository<WtxStrategyStateEntity, String> {
}
