package com.riskdesk.application.service;

import com.riskdesk.domain.orderflow.event.AbsorptionDetected;
import com.riskdesk.domain.orderflow.event.DistributionSetupDetected;
import com.riskdesk.domain.orderflow.event.FlashCrashPhaseChanged;
import com.riskdesk.domain.orderflow.event.IcebergDetected;
import com.riskdesk.domain.orderflow.event.MomentumBurstDetected;
import com.riskdesk.domain.orderflow.event.SmartMoneyCycleDetected;
import com.riskdesk.domain.orderflow.event.SpoofingDetected;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.orderflow.model.DistributionSignal;
import com.riskdesk.domain.orderflow.model.IcebergSignal;
import com.riskdesk.domain.orderflow.model.MomentumSignal;
import com.riskdesk.domain.orderflow.model.SmartMoneyCycleSignal;
import com.riskdesk.domain.orderflow.model.SpoofingSignal;
import com.riskdesk.infrastructure.persistence.JpaAbsorptionEventRepository;
import com.riskdesk.infrastructure.persistence.JpaCycleEventRepository;
import com.riskdesk.infrastructure.persistence.JpaDistributionEventRepository;
import com.riskdesk.infrastructure.persistence.JpaFlashCrashEventRepository;
import com.riskdesk.infrastructure.persistence.JpaFootprintBarRepository;
import com.riskdesk.infrastructure.persistence.JpaIcebergEventRepository;
import com.riskdesk.infrastructure.persistence.JpaMomentumEventRepository;
import com.riskdesk.infrastructure.persistence.JpaSpoofingEventRepository;
import com.riskdesk.infrastructure.persistence.entity.AbsorptionEventEntity;
import com.riskdesk.infrastructure.persistence.entity.CycleEventEntity;
import com.riskdesk.infrastructure.persistence.entity.DistributionEventEntity;
import com.riskdesk.infrastructure.persistence.entity.FlashCrashEventEntity;
import com.riskdesk.infrastructure.persistence.entity.IcebergEventEntity;
import com.riskdesk.infrastructure.persistence.entity.MomentumEventEntity;
import com.riskdesk.infrastructure.persistence.entity.SpoofingEventEntity;
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

    public OrderFlowEventPersistenceService(JpaAbsorptionEventRepository absorptionRepository,
                                            JpaSpoofingEventRepository spoofingRepository,
                                            JpaIcebergEventRepository icebergRepository,
                                            JpaFlashCrashEventRepository flashCrashRepository,
                                            JpaFootprintBarRepository footprintBarRepository,
                                            JpaDistributionEventRepository distributionRepository,
                                            JpaMomentumEventRepository momentumRepository,
                                            JpaCycleEventRepository cycleRepository) {
        this.absorptionRepository = absorptionRepository;
        this.spoofingRepository = spoofingRepository;
        this.icebergRepository = icebergRepository;
        this.flashCrashRepository = flashCrashRepository;
        this.footprintBarRepository = footprintBarRepository;
        this.distributionRepository = distributionRepository;
        this.momentumRepository = momentumRepository;
        this.cycleRepository = cycleRepository;
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
                    signal.totalVolume()
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
            if (absorption + spoofing + iceberg + flashCrash + footprint + distribution + momentum + cycle > 0) {
                log.info("Purged order flow events: {} absorption, {} spoofing, {} iceberg, {} flash crash, " +
                         "{} footprint, {} distribution, {} momentum, {} cycle",
                         absorption, spoofing, iceberg, flashCrash, footprint,
                         distribution, momentum, cycle);
            }
        } catch (Exception e) {
            log.error("Failed to purge expired order flow events: {}", e.getMessage(), e);
        }
    }
}
