package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.execution.MarketableExecutionSettings;
import com.riskdesk.domain.execution.port.MarketableSettingsRepositoryPort;
import com.riskdesk.infrastructure.persistence.entity.MarketableExecutionSettingsEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/** JPA-backed {@link MarketableSettingsRepositoryPort} — upserts the single {@code GLOBAL} settings row. */
@Component
public class JpaMarketableSettingsAdapter implements MarketableSettingsRepositoryPort {

    private final JpaMarketableExecutionSettingsRepository repository;

    public JpaMarketableSettingsAdapter(JpaMarketableExecutionSettingsRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<MarketableExecutionSettings> load() {
        return repository.findById(MarketableExecutionSettingsEntity.SINGLETON_ID)
            .map(e -> new MarketableExecutionSettings(e.isCloseEnabled(), e.isReverseOpenEnabled(), e.getCrossTicks()));
    }

    @Override
    public MarketableExecutionSettings save(MarketableExecutionSettings settings) {
        MarketableExecutionSettingsEntity entity = repository
            .findById(MarketableExecutionSettingsEntity.SINGLETON_ID)
            .orElseGet(MarketableExecutionSettingsEntity::new);
        entity.setId(MarketableExecutionSettingsEntity.SINGLETON_ID);
        entity.setCloseEnabled(settings.closeEnabled());
        entity.setReverseOpenEnabled(settings.reverseOpenEnabled());
        entity.setCrossTicks(settings.crossTicks());
        entity.setUpdatedAt(Instant.now());
        repository.saveAndFlush(entity);
        return settings;
    }
}
