package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.alert.port.AlertStateStore;
import com.riskdesk.infrastructure.persistence.entity.AlertEvaluatorCandleGuardEntity;
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

    private final AlertEvaluatorStateJpaRepository stateRepository;
    private final AlertEvaluatorCandleGuardJpaRepository candleGuardRepository;

    public JpaAlertStateStoreAdapter(AlertEvaluatorStateJpaRepository stateRepository,
                                     AlertEvaluatorCandleGuardJpaRepository candleGuardRepository) {
        this.stateRepository = stateRepository;
        this.candleGuardRepository = candleGuardRepository;
    }

    @Override
    public Map<String, String> loadRecent() {
        Instant since = Instant.now().minus(MAX_STATE_AGE_HOURS, ChronoUnit.HOURS);
        return stateRepository.findByUpdatedAtAfter(since).stream()
            .collect(Collectors.toMap(
                AlertEvaluatorStateEntity::getEvalKey,
                AlertEvaluatorStateEntity::getSignal
            ));
    }

    @Override
    public void save(String evalKey, String signal) {
        // evalKey is the @Id — JPA merge handles insert-or-update without a prior SELECT
        stateRepository.save(new AlertEvaluatorStateEntity(evalKey, signal, Instant.now()));
    }

    @Override
    public void remove(String evalKey) {
        stateRepository.deleteById(evalKey);
    }

    // ── PR-7 · Candle-close guards ──────────────────────────────────────

    @Override
    public Map<String, Instant> loadRecentCandleGuards() {
        Instant since = Instant.now().minus(MAX_STATE_AGE_HOURS, ChronoUnit.HOURS);
        return candleGuardRepository.findByUpdatedAtAfter(since).stream()
            .collect(Collectors.toMap(
                AlertEvaluatorCandleGuardEntity::getEvalKey,
                AlertEvaluatorCandleGuardEntity::getCandleTimestamp
            ));
    }

    @Override
    public void saveCandleGuard(String evalKey, Instant candleTimestamp) {
        candleGuardRepository.save(
            new AlertEvaluatorCandleGuardEntity(evalKey, candleTimestamp, Instant.now()));
    }

    @Override
    public void removeCandleGuard(String evalKey) {
        // existsById avoids the JPA "no entity with id X exists" warning on stale rollover cleanup
        if (candleGuardRepository.existsById(evalKey)) {
            candleGuardRepository.deleteById(evalKey);
        }
    }
}
