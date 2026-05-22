package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.playbook.automation.PlaybookAutomationState;
import com.riskdesk.domain.playbook.automation.port.PlaybookAutomationStatePort;
import com.riskdesk.infrastructure.persistence.entity.PlaybookAutomationStateEntity;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class JpaPlaybookAutomationStateAdapter implements PlaybookAutomationStatePort {

    private final JpaPlaybookAutomationStateRepository repository;

    public JpaPlaybookAutomationStateAdapter(JpaPlaybookAutomationStateRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<PlaybookAutomationState> load(String instrument, String timeframe) {
        if (instrument == null || timeframe == null) {
            return Optional.empty();
        }
        return repository.findByInstrumentAndTimeframe(instrument, timeframe)
            .map(PlaybookAutomationStateMapper::toDomain);
    }

    @Override
    public PlaybookAutomationState save(PlaybookAutomationState state) {
        PlaybookAutomationStateEntity entity = repository
            .findByInstrumentAndTimeframe(state.instrument(), state.timeframe())
            .orElseGet(PlaybookAutomationStateEntity::new);
        PlaybookAutomationStateMapper.fromDomain(state, entity);
        return PlaybookAutomationStateMapper.toDomain(repository.saveAndFlush(entity));
    }
}
