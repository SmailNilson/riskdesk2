package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.TradeDecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradeDecisionJpaRepository extends JpaRepository<TradeDecisionEntity, Long> {

    List<TradeDecisionEntity> findByInstrumentAndTimeframeAndDirectionAndZoneNameOrderByRevisionAsc(
        String instrument, String timeframe, String direction, String zoneName);
}
