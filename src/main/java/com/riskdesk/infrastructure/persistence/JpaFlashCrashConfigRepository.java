package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.FlashCrashConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface JpaFlashCrashConfigRepository extends JpaRepository<FlashCrashConfigEntity, Long> {

    Optional<FlashCrashConfigEntity> findByInstrument(Instrument instrument);

    void deleteByInstrument(Instrument instrument);
}
