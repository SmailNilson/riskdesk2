package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.domain.simulation.ReviewType;
import com.riskdesk.infrastructure.persistence.entity.TradeSimulationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JpaTradeSimulationRepository extends JpaRepository<TradeSimulationEntity, Long> {

    Optional<TradeSimulationEntity> findByReviewIdAndReviewType(long reviewId, ReviewType reviewType);

    List<TradeSimulationEntity> findByInstrumentOrderByCreatedAtDesc(String instrument, Pageable pageable);

    List<TradeSimulationEntity> findBySimulationStatusIn(Collection<TradeSimulationStatus> statuses);

    List<TradeSimulationEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
