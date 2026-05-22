package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.playbook.automation.PlaybookDecision;
import com.riskdesk.domain.playbook.automation.port.PlaybookDecisionRepositoryPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class JpaPlaybookDecisionRepositoryAdapter implements PlaybookDecisionRepositoryPort {

    private final JpaPlaybookDecisionRepository repository;

    public JpaPlaybookDecisionRepositoryAdapter(JpaPlaybookDecisionRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<PlaybookDecision> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id).map(PlaybookDecisionMapper::toDomain);
    }

    @Override
    public Optional<PlaybookDecision> findByDecisionKey(String decisionKey) {
        if (decisionKey == null || decisionKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findByDecisionKey(decisionKey).map(PlaybookDecisionMapper::toDomain);
    }

    @Override
    public List<PlaybookDecision> findRecent(String instrument, String timeframe, int limit) {
        if (instrument == null || instrument.isBlank()
            || timeframe == null || timeframe.isBlank()
            || limit <= 0) {
            return List.of();
        }
        return repository
            .findByInstrumentAndTimeframeOrderByCreatedAtDesc(
                instrument, timeframe, PageRequest.of(0, limit))
            .stream()
            .map(PlaybookDecisionMapper::toDomain)
            .toList();
    }

    @Override
    public PlaybookDecision createIfAbsent(PlaybookDecision decision) {
        return findByDecisionKey(decision.decisionKey())
            .orElseGet(() -> saveNew(decision));
    }

    @Override
    public PlaybookDecision save(PlaybookDecision decision) {
        return PlaybookDecisionMapper.toDomain(
            repository.saveAndFlush(PlaybookDecisionMapper.toEntity(decision))
        );
    }

    private PlaybookDecision saveNew(PlaybookDecision decision) {
        try {
            return save(decision);
        } catch (DataIntegrityViolationException e) {
            return findByDecisionKey(decision.decisionKey()).orElseThrow(() -> e);
        }
    }
}
