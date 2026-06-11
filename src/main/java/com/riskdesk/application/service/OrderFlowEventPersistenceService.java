package com.riskdesk.application.service;

import com.riskdesk.domain.orderflow.event.AbsorptionDetected;
import com.riskdesk.domain.orderflow.event.DistributionSetupDetected;
import com.riskdesk.domain.orderflow.event.FlashCrashPhaseChanged;
import com.riskdesk.domain.orderflow.event.FootprintBarClosed;
import com.riskdesk.domain.orderflow.event.IcebergDetected;
import com.riskdesk.domain.orderflow.event.MomentumBurstDetected;
import com.riskdesk.domain.orderflow.event.SmartMoneyCycleDetected;
import com.riskdesk.domain.orderflow.event.SpoofingDetected;
import com.riskdesk.domain.orderflow.event.WallEpisodeClosed;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.orderflow.model.DistributionSignal;
import com.riskdesk.domain.orderflow.model.IcebergSignal;
import com.riskdesk.domain.orderflow.model.MomentumSignal;
import com.riskdesk.domain.orderflow.model.SmartMoneyCycleSignal;
import com.riskdesk.domain.orderflow.model.FootprintBar;
import com.riskdesk.domain.orderflow.model.SpoofingSignal;
import com.riskdesk.domain.orderflow.model.WallEpisode;
import com.riskdesk.infrastructure.persistence.JpaAbsorptionEventRepository;
import com.riskdesk.infrastructure.persistence.JpaCycleEventRepository;
import com.riskdesk.infrastructure.persistence.JpaDistributionEventRepository;
import com.riskdesk.infrastructure.persistence.JpaFlashCrashEventRepository;
import com.riskdesk.infrastructure.persistence.JpaFootprintBarRepository;
import com.riskdesk.infrastructure.persistence.JpaIcebergEventRepository;
import com.riskdesk.infrastructure.persistence.JpaMomentumEventRepository;
import com.riskdesk.infrastructure.persistence.JpaSpoofingEventRepository;
import com.riskdesk.infrastructure.persistence.JpaWallEpisodeRepository;
import com.riskdesk.infrastructure.persistence.entity.AbsorptionEventEntity;
import com.riskdesk.infrastructure.persistence.entity.CycleEventEntity;
import com.riskdesk.infrastructure.persistence.entity.DistributionEventEntity;
import com.riskdesk.infrastructure.persistence.entity.FlashCrashEventEntity;
import com.riskdesk.infrastructure.persistence.entity.IcebergEventEntity;
import com.riskdesk.infrastructure.persistence.entity.MomentumEventEntity;
import com.riskdesk.infrastructure.persistence.entity.FootprintBarEntity;
import com.riskdesk.infrastructure.persistence.entity.SpoofingEventEntity;
import com.riskdesk.infrastructure.persistence.entity.WallEpisodeEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Persists order flow domain events to the database for historical analysis.
 * <p>
 * Listens for domain events published by the order flow detection engines
 * (absorption, spoofing, iceberg, flash crash) and saves them asynchronously
 * to avoid blocking the event publisher thread.
 * <p>
 * Also runs a daily purge job to remove events older than 90 days.
 */
@Service
public class OrderFlowEventPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(OrderFlowEventPersistenceService.class);
    private static final int EVENT_RETENTION_DAYS = 90;

    private final JpaAbsorptionEventRepository absorptionRepository;
    private final JpaSpoofingEventRepository spoofingRepository;
    private final JpaIcebergEventRepository icebergRepository;
    private final JpaFlashCrashEventRepository flashCrashRepository;
    private final JpaFootprintBarRepository footprintBarRepository;
    private final JpaDistributionEventRepository distributionRepository;
    private final JpaMomentumEventRepository momentumRepository;
    private final JpaCycleEventRepository cycleRepository;
    private final JpaWallEpisodeRepository wallEpisodeRepository;
    private final com.riskdesk.infrastructure.persistence.JpaQuantScanSnapshotRepository quantScanSnapshotRepository;
    private final ObjectMapper objectMapper;

    public OrderFlowEventPersistenceService(JpaAbsorptionEventRepository absorptionRepository,
                                            JpaSpoofingEventRepository spoofingRepository,
                                            JpaIcebergEventRepository icebergRepository,
                                            JpaFlashCrashEventRepository flashCrashRepository,
                                            JpaFootprintBarRepository footprintBarRepository,
                                            JpaDistributionEventRepository distributionRepository,
                                            JpaMomentumEventRepository momentumRepository,
                                            JpaCycleEventRepository cycleRepository,
                                            JpaWallEpisodeRepository wallEpisodeRepository,
                                            com.riskdesk.infrastructure.persistence.JpaQuantScanSnapshotRepository quantScanSnapshotRepository,
                                            ObjectMapper objectMapper) {
        this.absorptionRepository = absorptionRepository;
        this.spoofingRepository = spoofingRepository;
        this.icebergRepository = icebergRepository;
        this.flashCrashRepository = flashCrashRepository;
        this.footprintBarRepository = footprintBarRepository;
        this.distributionRepository = distributionRepository;
        this.momentumRepository = momentumRepository;
        this.cycleRepository = cycleRepository;
        this.wallEpisodeRepository = wallEpisodeRepository;
        this.quantScanSnapshotRepository = quantScanSnapshotRepository;
        this.objectMapper = objectMapper;
    }

    @Async
    @EventListener
    @Transactional
    public void onWallEpisodeClosed(WallEpisodeClosed event) {
        try {
            WallEpisode ep = event.episode();
            wallEpisodeRepository.save(new WallEpisodeEntity(
                    event.instrument(),
                    ep.endedAt() != null ? ep.endedAt() : event.timestamp(),
                    ep.side().name(),
                    ep.price(),
                    ep.initialSize(),
                    ep.maxSize(),
                    ep.lastSize(),
                    ep.firstSeenAt(),
                    ep.durationSeconds(),
                    ep.outcome().name(),
                    // NaN (side unavailable at finalization) is not representable in a
                    // NOT NULL double column — store 0 and rely on outcome OUT_OF_RANGE.
                    Double.isNaN(ep.endDistanceTicks()) ? 0.0 : ep.endDistanceTicks()
            ));
        } catch (Exception e) {
            log.warn("Failed to persist wall episode for {}: {}", event.instrument(), e.getMessage());
        }
    }

    @Async
    @EventListener
    @Transactional
    public void onAbsorptionDetected(AbsorptionDetected event) {
        try {
            AbsorptionSignal signal = event.signal();
            var entity = new AbsorptionEventEntity(
                    event.instrument(),
                    event.timestamp(),
                    signal.side().name(),
                    signal.absorptionScore(),
                    signal.aggressiveDelta(),
                    signal.priceMoveTicks(),
                    signal.totalVolume(),
                    signal.absorptionType() != null ? signal.absorptionType().name() : null,
                    signal.explanation()
            );
            absorptionRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist absorption event for {}: {}", event.instrument(), e.getMessage());
        }
    }

    @Async
    @EventListener
    @Transactional
    public void onSpoofingDetected(SpoofingDetected event) {
        try {
            SpoofingSignal signal = event.signal();
            var entity = new SpoofingEventEntity(
                    event.instrument(),
                    event.timestamp(),
                    signal.side().name(),
                    signal.priceLevel(),
                    signal.wallSize(),
                    signal.durationSeconds(),
                    signal.priceCrossed(),
                    signal.spoofScore()
            );
            spoofingRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist spoofing event for {}: {}", event.instrument(), e.getMessage());
        }
    }

    @Async
    @EventListener
    @Transactional
    public void onIcebergDetected(IcebergDetected event) {
        try {
            IcebergSignal signal = event.signal();
            var entity = new IcebergEventEntity(
                    event.instrument(),
                    event.timestamp(),
                    signal.side().name(),
                    signal.priceLevel(),
                    signal.rechargeCount(),
                    signal.avgRechargeSize(),
                    signal.durationSeconds(),
                    signal.icebergScore()
            );
            icebergRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist iceberg event for {}: {}", event.instrument(), e.getMessage());
        }
    }

    @Async
    @EventListener
    @Transactional
    public void onDistributionSetupDetected(DistributionSetupDetected event) {
        try {
            DistributionSignal signal = event.signal();
            var entity = new DistributionEventEntity(
                    event.instrument(),
                    event.timestamp(),
                    signal.type().name(),
                    signal.consecutiveCount(),
                    signal.avgScore(),
                    signal.totalDurationSeconds(),
                    signal.priceAtDetection(),
                    signal.resistanceLevel(),
                    signal.confidenceScore()
            );
            distributionRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist distribution event for {}: {}", event.instrument(), e.getMessage());
        }
    }

    @Async
    @EventListener
    @Transactional
    public void onMomentumBurstDetected(MomentumBurstDetected event) {
        try {
            MomentumSignal signal = event.signal();
            var entity = new MomentumEventEntity(
                    event.instrument(),
                    event.timestamp(),
                    signal.side().name(),
                    signal.momentumScore(),
                    signal.aggressiveDelta(),
                    signal.priceMoveTicks(),
                    signal.priceMovePoints(),
                    signal.totalVolume()
            );
            momentumRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist momentum event for {}: {}", event.instrument(), e.getMessage());
        }
    }

    @Async
    @EventListener
    @Transactional
    public void onSmartMoneyCycleDetected(SmartMoneyCycleDetected event) {
        try {
            SmartMoneyCycleSignal signal = event.signal();
            var entity = new CycleEventEntity(
                    event.instrument(),
                    event.timestamp(),
                    signal.cycleType() != null ? signal.cycleType().name() : null,
                    signal.currentPhase().name(),
                    signal.priceAtPhase1(),
                    signal.priceAtPhase2(),
                    signal.priceAtPhase3(),
                    signal.totalPriceMove(),
                    signal.totalDurationMinutes(),
                    signal.confidence(),
                    signal.startedAt(),
                    signal.completedAt()
            );
            cycleRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist cycle event for {}: {}", event.instrument(), e.getMessage());
        }
    }

    @Async
    @EventListener
    @Transactional
    public void onFlashCrashPhaseChanged(FlashCrashPhaseChanged event) {
        try {
            var entity = new FlashCrashEventEntity(
                    event.instrument(),
                    event.timestamp(),
                    event.previousPhase().name(),
                    event.currentPhase().name(),
                    event.conditionsMet(),
                    event.reversalScore()
            );
            flashCrashRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist flash crash event for {}: {}", event.instrument(), e.getMessage());
        }
    }

    /**
     * Persists each closed footprint bar (UC-OF-011). The per-bucket volume profile is
     * serialized to JSON; the (instrument, timeframe, barTimestamp) unique constraint
     * makes re-publication idempotent.
     */
    @Async
    @EventListener
    @Transactional
    public void onFootprintBarClosed(FootprintBarClosed event) {
        try {
            FootprintBar bar = event.bar();
            Instrument instrument = Instrument.valueOf(bar.instrument());
            Instant barTimestamp = Instant.ofEpochSecond(bar.barTimestamp());
            if (footprintBarRepository.findByInstrumentAndTimeframeAndBarTimestamp(
                    instrument, bar.timeframe(), barTimestamp).isPresent()) {
                return;
            }
            String profileJson = objectMapper.writeValueAsString(bar.levels().values());
            footprintBarRepository.save(new FootprintBarEntity(
                    instrument,
                    bar.timeframe(),
                    barTimestamp,
                    bar.pocPrice(),
                    bar.totalBuyVolume(),
                    bar.totalSellVolume(),
                    bar.totalDelta(),
                    profileJson
            ));
        } catch (Exception e) {
            log.warn("Failed to persist footprint bar for {}: {}", event.bar().instrument(), e.getMessage());
        }
    }

    /**
     * Daily purge of order flow events older than 90 days.
     * Runs at 03:23 UTC to avoid peak hours and the :00/:30 minute marks.
     * Also purges footprint bars (same 90-day retention).
     */
    @Scheduled(cron = "0 23 3 * * *")
    @Transactional
    public void purgeExpiredEvents() {
        Instant cutoff = Instant.now().minus(EVENT_RETENTION_DAYS, ChronoUnit.DAYS);
        log.info("Purging order flow events older than {} (retention: {} days)", cutoff, EVENT_RETENTION_DAYS);
        try {
            int absorption = absorptionRepository.deleteByTimestampBefore(cutoff);
            int spoofing = spoofingRepository.deleteByTimestampBefore(cutoff);
            int iceberg = icebergRepository.deleteByTimestampBefore(cutoff);
            int flashCrash = flashCrashRepository.deleteByTimestampBefore(cutoff);
            int footprint = footprintBarRepository.deleteByTimestampBefore(cutoff);
            int distribution = distributionRepository.deleteByTimestampBefore(cutoff);
            int momentum = momentumRepository.deleteByTimestampBefore(cutoff);
            int cycle = cycleRepository.deleteByTimestampBefore(cutoff);
            int walls = wallEpisodeRepository.deleteByTimestampBefore(cutoff);
            int quantScans = quantScanSnapshotRepository.deleteByScannedAtBefore(cutoff);
            if (absorption + spoofing + iceberg + flashCrash + footprint + distribution + momentum + cycle + walls + quantScans > 0) {
                log.info("Purged order flow events: {} absorption, {} spoofing, {} iceberg, {} flash crash, " +
                         "{} footprint, {} distribution, {} momentum, {} cycle, {} wall episodes, {} quant scans",
                         absorption, spoofing, iceberg, flashCrash, footprint,
                         distribution, momentum, cycle, walls, quantScans);
            }
        } catch (Exception e) {
            log.error("Failed to purge expired order flow events: {}", e.getMessage(), e);
        }
    }
}
