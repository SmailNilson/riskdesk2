package com.riskdesk.application.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.JpaAbsorptionEventRepository;
import com.riskdesk.infrastructure.persistence.JpaCycleEventRepository;
import com.riskdesk.infrastructure.persistence.JpaDistributionEventRepository;
import com.riskdesk.infrastructure.persistence.JpaIcebergEventRepository;
import com.riskdesk.infrastructure.persistence.JpaMomentumEventRepository;
import com.riskdesk.infrastructure.persistence.JpaSpoofingEventRepository;
import com.riskdesk.infrastructure.persistence.entity.AbsorptionEventEntity;
import com.riskdesk.infrastructure.persistence.entity.CycleEventEntity;
import com.riskdesk.infrastructure.persistence.entity.DistributionEventEntity;
import com.riskdesk.infrastructure.persistence.entity.IcebergEventEntity;
import com.riskdesk.infrastructure.persistence.entity.MomentumEventEntity;
import com.riskdesk.infrastructure.persistence.entity.SpoofingEventEntity;
import com.riskdesk.application.dto.AbsorptionEventView;
import com.riskdesk.application.dto.CycleEventView;
import com.riskdesk.application.dto.DistributionEventView;
import com.riskdesk.application.dto.IcebergEventView;
import com.riskdesk.application.dto.MomentumEventView;
import com.riskdesk.application.dto.SpoofingEventView;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Application service that exposes persisted order-flow events (iceberg, absorption,
 * spoofing) to the presentation layer. Keeps the controller free of raw JPA calls
 * and DTO mapping, preserving hexagonal discipline.
 *
 * <p>All queries return the most recent N events for a given instrument, newest first.
 * Results come from the {@code order_flow_*_events} tables populated by
 * {@link OrderFlowEventPersistenceService} (90-day retention).</p>
 */
@Service
public class OrderFlowHistoryService {

    private static final int MAX_LIMIT = 200;

    private final JpaIcebergEventRepository icebergRepository;
    private final JpaAbsorptionEventRepository absorptionRepository;
    private final JpaSpoofingEventRepository spoofingRepository;
    private final JpaDistributionEventRepository distributionRepository;
    private final JpaMomentumEventRepository momentumRepository;
    private final JpaCycleEventRepository cycleRepository;

    public OrderFlowHistoryService(JpaIcebergEventRepository icebergRepository,
                                   JpaAbsorptionEventRepository absorptionRepository,
                                   JpaSpoofingEventRepository spoofingRepository,
                                   JpaDistributionEventRepository distributionRepository,
                                   JpaMomentumEventRepository momentumRepository,
                                   JpaCycleEventRepository cycleRepository) {
        this.icebergRepository = icebergRepository;
        this.absorptionRepository = absorptionRepository;
        this.spoofingRepository = spoofingRepository;
        this.distributionRepository = distributionRepository;
        this.momentumRepository = momentumRepository;
        this.cycleRepository = cycleRepository;
    }

    @Transactional(readOnly = true)
    public List<IcebergEventView> recentIcebergs(Instrument instrument, int limit) {
        int capped = clampLimit(limit);
        List<IcebergEventEntity> rows =
            icebergRepository.findByInstrumentOrderByTimestampDesc(instrument, PageRequest.of(0, capped));
        return rows.stream().map(OrderFlowHistoryService::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<AbsorptionEventView> recentAbsorptions(Instrument instrument, int limit) {
        int capped = clampLimit(limit);
        List<AbsorptionEventEntity> rows =
            absorptionRepository.findByInstrumentOrderByTimestampDesc(instrument, PageRequest.of(0, capped));
        return rows.stream().map(OrderFlowHistoryService::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<SpoofingEventView> recentSpoofings(Instrument instrument, int limit) {
        int capped = clampLimit(limit);
        List<SpoofingEventEntity> rows =
            spoofingRepository.findByInstrumentOrderByTimestampDesc(instrument, PageRequest.of(0, capped));
        return rows.stream().map(OrderFlowHistoryService::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<DistributionEventView> recentDistributions(Instrument instrument, int limit) {
        int capped = clampLimit(limit);
        List<DistributionEventEntity> rows =
            distributionRepository.findByInstrumentOrderByTimestampDesc(instrument, PageRequest.of(0, capped));
        return rows.stream().map(OrderFlowHistoryService::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<MomentumEventView> recentMomentumBursts(Instrument instrument, int limit) {
        int capped = clampLimit(limit);
        List<MomentumEventEntity> rows =
            momentumRepository.findByInstrumentOrderByTimestampDesc(instrument, PageRequest.of(0, capped));
        return rows.stream().map(OrderFlowHistoryService::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<CycleEventView> recentCycles(Instrument instrument, int limit) {
        int capped = clampLimit(limit);
        List<CycleEventEntity> rows =
            cycleRepository.findByInstrumentOrderByTimestampDesc(instrument, PageRequest.of(0, capped));
        return rows.stream().map(OrderFlowHistoryService::toView).toList();
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) return 1;
        return Math.min(limit, MAX_LIMIT);
    }

    // -- Mapping helpers (entity -> presentation DTO) ---------------------------

    private static IcebergEventView toView(IcebergEventEntity e) {
        return new IcebergEventView(
            e.getInstrument().name(),
            e.getTimestamp(),
            e.getSide(),
            e.getPriceLevel(),
            e.getRechargeCount(),
            e.getAvgRechargeSize(),
            e.getDurationSeconds(),
            e.getIcebergScore()
        );
    }

    private static AbsorptionEventView toView(AbsorptionEventEntity e) {
        return new AbsorptionEventView(
            e.getInstrument().name(),
            e.getTimestamp(),
            e.getSide(),
            e.getAbsorptionScore(),
            e.getAggressiveDelta(),
            e.getPriceMoveTicks(),
            e.getTotalVolume()
        );
    }

    private static SpoofingEventView toView(SpoofingEventEntity e) {
        return new SpoofingEventView(
            e.getInstrument().name(),
            e.getTimestamp(),
            e.getSide(),
            e.getPriceLevel(),
            e.getWallSize(),
            e.getDurationSeconds(),
            e.isPriceCrossed(),
            e.getSpoofScore()
        );
    }

    private static DistributionEventView toView(DistributionEventEntity e) {
        return new DistributionEventView(
            e.getInstrument().name(),
            e.getTimestamp(),
            e.getType(),
            e.getConsecutiveCount(),
            e.getAvgScore(),
            e.getTotalDurationSeconds(),
            e.getPriceAtDetection(),
            e.getResistanceLevel(),
            e.getConfidenceScore()
        );
    }

    private static MomentumEventView toView(MomentumEventEntity e) {
        return new MomentumEventView(
            e.getInstrument().name(),
            e.getTimestamp(),
            e.getSide(),
            e.getMomentumScore(),
            e.getAggressiveDelta(),
            e.getPriceMoveTicks(),
            e.getPriceMovePoints(),
            e.getTotalVolume()
        );
    }

    private static CycleEventView toView(CycleEventEntity e) {
        return new CycleEventView(
            e.getInstrument().name(),
            e.getTimestamp(),
            e.getCycleType(),
            e.getCurrentPhase(),
            e.getPriceAtPhase1(),
            e.getPriceAtPhase2(),
            e.getPriceAtPhase3(),
            e.getTotalPriceMove(),
            e.getTotalDurationMinutes(),
            e.getConfidence(),
            e.getStartedAt(),
            e.getCompletedAt()
        );
    }
}
