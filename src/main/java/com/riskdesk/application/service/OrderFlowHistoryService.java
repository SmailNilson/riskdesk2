package com.riskdesk.application.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.JpaAbsorptionEventRepository;
import com.riskdesk.infrastructure.persistence.JpaIcebergEventRepository;
import com.riskdesk.infrastructure.persistence.JpaSpoofingEventRepository;
import com.riskdesk.infrastructure.persistence.entity.AbsorptionEventEntity;
import com.riskdesk.infrastructure.persistence.entity.IcebergEventEntity;
import com.riskdesk.infrastructure.persistence.entity.SpoofingEventEntity;
import com.riskdesk.application.dto.AbsorptionEventView;
import com.riskdesk.application.dto.IcebergEventView;
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

    public OrderFlowHistoryService(JpaIcebergEventRepository icebergRepository,
                                   JpaAbsorptionEventRepository absorptionRepository,
                                   JpaSpoofingEventRepository spoofingRepository) {
        this.icebergRepository = icebergRepository;
        this.absorptionRepository = absorptionRepository;
        this.spoofingRepository = spoofingRepository;
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
}
