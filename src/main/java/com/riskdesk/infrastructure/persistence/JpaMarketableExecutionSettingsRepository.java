package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.MarketableExecutionSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for the singleton marketable-execution settings row. */
public interface JpaMarketableExecutionSettingsRepository
    extends JpaRepository<MarketableExecutionSettingsEntity, String> {
}
