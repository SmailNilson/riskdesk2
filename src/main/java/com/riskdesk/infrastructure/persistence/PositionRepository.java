package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.PositionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;

public interface PositionRepository extends JpaRepository<PositionEntity, Long> {

    List<PositionEntity> findByOpenTrue();

    List<PositionEntity> findByOpenTrueAndInstrument(Instrument instrument);

    List<PositionEntity> findByOpenFalseOrderByClosedAtDesc();

    @Query("SELECT COALESCE(SUM(p.unrealizedPnL), 0) FROM PositionEntity p WHERE p.open = true")
    BigDecimal totalUnrealizedPnL();

    @Query("SELECT COALESCE(SUM(p.realizedPnL), 0) FROM PositionEntity p WHERE p.open = false AND p.closedAt >= CURRENT_DATE")
    BigDecimal todayRealizedPnL();

    @Query("SELECT COUNT(p) FROM PositionEntity p WHERE p.open = true")
    long openPositionCount();
}
