package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Position;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.math.BigDecimal;
import java.util.List;

public interface PositionRepository extends JpaRepository<Position, Long> {

    List<Position> findByOpenTrue();

    List<Position> findByOpenTrueAndInstrument(Instrument instrument);

    List<Position> findByOpenFalseOrderByClosedAtDesc();

    @Query("SELECT COALESCE(SUM(p.unrealizedPnL), 0) FROM Position p WHERE p.open = true")
    BigDecimal totalUnrealizedPnL();

    @Query("SELECT COALESCE(SUM(p.realizedPnL), 0) FROM Position p WHERE p.open = false AND p.closedAt >= CURRENT_DATE")
    BigDecimal todayRealizedPnL();

    @Query("SELECT COUNT(p) FROM Position p WHERE p.open = true")
    long openPositionCount();
}
