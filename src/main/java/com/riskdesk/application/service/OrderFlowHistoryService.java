package com.riskdesk.application.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import com.riskdesk.infrastructure.persistence.JpaAbsorptionEventRepository;
import com.riskdesk.infrastructure.persistence.JpaCycleEventRepository;
import com.riskdesk.infrastructure.persistence.JpaDistributionEventRepository;
import com.riskdesk.infrastructure.persistence.JpaFootprintBarRepository;
import com.riskdesk.infrastructure.persistence.JpaIcebergEventRepository;
import com.riskdesk.infrastructure.persistence.JpaMomentumEventRepository;
import com.riskdesk.infrastructure.persistence.JpaSpoofingEventRepository;
import com.riskdesk.infrastructure.persistence.JpaWallEpisodeRepository;
import com.riskdesk.infrastructure.persistence.entity.AbsorptionEventEntity;
import com.riskdesk.infrastructure.persistence.entity.FootprintBarEntity;
import com.riskdesk.infrastructure.persistence.entity.CycleEventEntity;
import com.riskdesk.infrastructure.persistence.entity.DistributionEventEntity;
import com.riskdesk.infrastructure.persistence.entity.IcebergEventEntity;
import com.riskdesk.infrastructure.persistence.entity.MomentumEventEntity;
import com.riskdesk.infrastructure.persistence.entity.SpoofingEventEntity;
import com.riskdesk.infrastructure.persistence.entity.WallEpisodeEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.orderflow.model.FootprintBar;
import com.riskdesk.domain.orderflow.model.FootprintLevel;
import com.riskdesk.domain.orderflow.service.FootprintImbalanceCalculator;
import com.riskdesk.application.dto.AbsorptionEventView;
import com.riskdesk.application.dto.CycleEventView;
import com.riskdesk.application.dto.DistributionEventView;
import com.riskdesk.application.dto.IcebergEventView;
import com.riskdesk.application.dto.MomentumEventView;
import com.riskdesk.application.dto.SpoofingEventView;
import com.riskdesk.application.dto.WallEpisodeView;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final JpaFootprintBarRepository footprintBarRepository;
    private final JpaWallEpisodeRepository wallEpisodeRepository;
    private final OrderFlowProperties properties;
    private final ObjectMapper objectMapper;

    public OrderFlowHistoryService(JpaIcebergEventRepository icebergRepository,
                                   JpaAbsorptionEventRepository absorptionRepository,
                                   JpaSpoofingEventRepository spoofingRepository,
                                   JpaDistributionEventRepository distributionRepository,
                                   JpaMomentumEventRepository momentumRepository,
                                   JpaCycleEventRepository cycleRepository,
                                   JpaFootprintBarRepository footprintBarRepository,
                                   JpaWallEpisodeRepository wallEpisodeRepository,
                                   OrderFlowProperties properties,
                                   ObjectMapper objectMapper) {
        this.icebergRepository = icebergRepository;
        this.absorptionRepository = absorptionRepository;
        this.spoofingRepository = spoofingRepository;
        this.distributionRepository = distributionRepository;
        this.momentumRepository = momentumRepository;
        this.cycleRepository = cycleRepository;
        this.footprintBarRepository = footprintBarRepository;
        this.wallEpisodeRepository = wallEpisodeRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Most recent closed wall episodes (UC-OF-012) for an instrument, newest first. */
    @Transactional(readOnly = true)
    public List<WallEpisodeView> recentWallEpisodes(Instrument instrument, int limit) {
        int capped = clampLimit(limit);
        List<WallEpisodeEntity> rows =
            wallEpisodeRepository.findByInstrumentOrderByTimestampDesc(instrument, PageRequest.of(0, capped));
        return rows.stream().map(OrderFlowHistoryService::toView).toList();
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
        // Display calibration: history mirrors the WS filter — only events above the
        // per-instrument display score (raw emission is ~1100/day on MNQ).
        double minScore = properties.getAbsorption().minDisplayScoreFor(instrument.name());
        List<AbsorptionEventEntity> rows =
            absorptionRepository.findByInstrumentAndAbsorptionScoreGreaterThanEqualOrderByTimestampDesc(
                instrument, minScore, PageRequest.of(0, capped));
        return rows.stream().map(OrderFlowHistoryService::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<SpoofingEventView> recentSpoofings(Instrument instrument, int limit) {
        int capped = clampLimit(limit);
        double minScore = properties.getSpoofing().minDisplayScoreFor(instrument.name());
        List<SpoofingEventEntity> rows =
            spoofingRepository.findByInstrumentAndSpoofScoreGreaterThanEqualOrderByTimestampDesc(
                instrument, minScore, PageRequest.of(0, capped));
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
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(properties.getMomentum().getHistoryMaxAgeMinutes()));
        List<MomentumEventEntity> rows =
            momentumRepository.findByInstrumentAndTimestampAfterOrderByTimestampDesc(
                instrument, cutoff, PageRequest.of(0, capped));
        return rows.stream().map(OrderFlowHistoryService::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<CycleEventView> recentCycles(Instrument instrument, int limit) {
        int capped = clampLimit(limit);
        int minConfidence = properties.getCycle().getMinConfidence();
        List<CycleEventEntity> rows =
            cycleRepository.findByInstrumentAndConfidenceGreaterThanEqualOrderByTimestampDesc(
                instrument, minConfidence, PageRequest.of(0, capped));
        return rows.stream().map(OrderFlowHistoryService::toView).toList();
    }

    /**
     * Most recent closed footprint bars for an instrument, newest first. The per-bucket
     * profile is rebuilt from the persisted JSON; bars whose profile fails to parse are
     * returned with an empty level map rather than dropped.
     */
    @Transactional(readOnly = true)
    public List<FootprintBar> recentFootprintBars(Instrument instrument, int limit) {
        int capped = Math.min(Math.max(limit, 1), properties.getFootprint().getHistoryMaxBars());
        List<FootprintBarEntity> rows =
            footprintBarRepository.findByInstrumentOrderByBarTimestampDesc(instrument, PageRequest.of(0, capped));
        return rows.stream().map(this::toBar).toList();
    }

    FootprintBar toBar(FootprintBarEntity e) {
        Map<Double, FootprintLevel> levels = new LinkedHashMap<>();
        List<FootprintLevel> sorted = List.of();
        try {
            if (e.getProfileJson() != null && !e.getProfileJson().isBlank()) {
                List<FootprintLevel> parsed = objectMapper.readValue(
                    e.getProfileJson(), new TypeReference<List<FootprintLevel>>() {});
                sorted = parsed.stream()
                    .sorted(Comparator.comparingDouble(FootprintLevel::price))
                    .toList();
                for (FootprintLevel level : sorted) {
                    levels.put(level.price(), level);
                }
            }
        } catch (Exception ex) {
            // tolerate malformed legacy rows — headline metrics below remain valid
        }
        // Bar-level analysis is recomputed from the persisted level flags. Rows persisted
        // before the diagonal upgrade carry no diagonal flags (deserialized as false), so
        // their stacked zones are simply empty; unfinished-auction flags only need volumes
        // and therefore work for old rows too. The bucket size is the instrument's CURRENT
        // configured size — rows persisted under a different size cannot stack (adjacency
        // check fails), which is the safe behaviour.
        double bucketSize = footprintBucketSizeFor(e.getInstrument());
        return new FootprintBar(
            e.getInstrument().name(),
            e.getTimeframe(),
            e.getBarTimestamp().getEpochSecond(),
            levels,
            e.getPocPrice(),
            e.getTotalBuyVolume(),
            e.getTotalSellVolume(),
            e.getTotalDelta(),
            FootprintImbalanceCalculator.stackedZones(sorted, bucketSize, true),
            FootprintImbalanceCalculator.stackedZones(sorted, bucketSize, false),
            FootprintImbalanceCalculator.unfinishedHigh(sorted),
            FootprintImbalanceCalculator.unfinishedLow(sorted)
        );
    }

    /** Mirrors {@code IbkrFootprintAdapter}: configured bucket size or native tick size. */
    private double footprintBucketSizeFor(Instrument instrument) {
        Double configured = properties.getFootprint().getBucketSize().get(instrument.name());
        if (configured != null && configured > 0) {
            return configured;
        }
        return instrument.getTickSize().doubleValue();
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) return 1;
        return Math.min(limit, MAX_LIMIT);
    }

    // -- Mapping helpers (entity -> presentation DTO) ---------------------------

    private static WallEpisodeView toView(WallEpisodeEntity e) {
        return new WallEpisodeView(
            e.getInstrument().name(),
            e.getTimestamp(),
            e.getSide(),
            e.getPrice(),
            e.getInitialSize(),
            e.getMaxSize(),
            e.getLastSize(),
            e.getFirstSeenAt(),
            e.getDurationSeconds(),
            e.getOutcome(),
            e.getEndDistanceTicks()
        );
    }

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
        // Legacy rows pre-date absorptionType/explanation columns — default to CLASSIC + empty so frontend doesn't crash.
        String type = e.getAbsorptionType() != null ? e.getAbsorptionType() : "CLASSIC";
        String explanation = e.getExplanation() != null ? e.getExplanation() : "";
        return new AbsorptionEventView(
            e.getInstrument().name(),
            e.getTimestamp(),
            e.getSide(),
            e.getAbsorptionScore(),
            e.getAggressiveDelta(),
            e.getPriceMoveTicks(),
            e.getTotalVolume(),
            type,
            explanation
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
