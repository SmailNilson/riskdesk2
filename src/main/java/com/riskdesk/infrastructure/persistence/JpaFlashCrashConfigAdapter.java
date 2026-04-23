package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.FlashCrashThresholds;
import com.riskdesk.domain.orderflow.port.FlashCrashConfigPort;
import com.riskdesk.infrastructure.persistence.entity.FlashCrashConfigEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * JPA adapter implementing the FlashCrashConfigPort domain port.
 * Converts between FlashCrashThresholds domain record and FlashCrashConfigEntity.
 */
@Component
public class JpaFlashCrashConfigAdapter implements FlashCrashConfigPort {

    private final JpaFlashCrashConfigRepository repository;

    public JpaFlashCrashConfigAdapter(JpaFlashCrashConfigRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<FlashCrashThresholds> loadThresholds(Instrument instrument) {
        return repository.findByInstrument(instrument)
            .map(this::toDomain);
    }

    @Override
    @Transactional
    public void saveThresholds(Instrument instrument, FlashCrashThresholds thresholds) {
        FlashCrashConfigEntity entity = repository.findByInstrument(instrument)
            .orElseGet(() -> new FlashCrashConfigEntity(
                instrument,
                thresholds.velocityThreshold(),
                thresholds.delta5sThreshold(),
                thresholds.accelerationThreshold(),
                thresholds.depthImbalanceThreshold(),
                thresholds.volumeSpikeMultiplier(),
                thresholds.conditionsRequired(),
                Instant.now()
            ));

        entity.setVelocityThreshold(thresholds.velocityThreshold());
        entity.setDelta5sThreshold(thresholds.delta5sThreshold());
        entity.setAccelerationThreshold(thresholds.accelerationThreshold());
        entity.setDepthImbalanceThreshold(thresholds.depthImbalanceThreshold());
        entity.setVolumeSpikeMultiplier(thresholds.volumeSpikeMultiplier());
        entity.setConditionsRequired(thresholds.conditionsRequired());
        entity.setUpdatedAt(Instant.now());

        repository.save(entity);
    }

    @Override
    @Transactional
    public void deleteThresholds(Instrument instrument) {
        repository.deleteByInstrument(instrument);
    }

    private FlashCrashThresholds toDomain(FlashCrashConfigEntity entity) {
        return new FlashCrashThresholds(
            entity.getVelocityThreshold(),
            entity.getDelta5sThreshold(),
            entity.getAccelerationThreshold(),
            entity.getDepthImbalanceThreshold(),
            entity.getVolumeSpikeMultiplier(),
            entity.getConditionsRequired()
        );
    }
}
