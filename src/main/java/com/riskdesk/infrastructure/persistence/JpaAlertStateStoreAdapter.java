package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.alert.port.AlertStateStore;
import com.riskdesk.infrastructure.persistence.entity.AlertEvaluatorStateEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class JpaAlertStateStoreAdapter implements AlertStateStore {

    /** Only load states updated within the last 12 hours (stale state protection). */
    private static final long MAX_STATE_AGE_HOURS = 12;

    private final AlertEvaluatorStateJpaRepository repository;

    public JpaAlertStateStoreAdapter(AlertEvaluatorStateJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Map<String, String> loadRecent() {
        Instant since = Instant.now().minus(MAX_STATE_AGE_HOURS, ChronoUnit.HOURS);
        return repository.findByUpdatedAtAfter(since).stream()
            .collect(Collectors.toMap(
                AlertEvaluatorStateEntity::getEvalKey,
                AlertEvaluatorStateEntity::getSignal
            ));
    }

    @Override
    public void save(String evalKey, String signal) {
        // evalKey is the @Id — JPA merge handles insert-or-update without a prior SELECT
        repository.save(new AlertEvaluatorStateEntity(evalKey, signal, Instant.now()));
    }

    @Override
    public void remove(String evalKey) {
        repository.deleteById(evalKey);
    }
}
