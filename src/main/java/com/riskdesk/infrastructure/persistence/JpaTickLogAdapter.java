package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.port.TickLogPort;
import com.riskdesk.infrastructure.persistence.entity.DepthSnapshotEntity;
import com.riskdesk.infrastructure.persistence.entity.TickLogEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * JPA adapter implementing the TickLogPort domain port.
 * Persists raw trade ticks and depth snapshots for calibration data collection.
 */
@Component
public class JpaTickLogAdapter implements TickLogPort {

    private static final Logger log = LoggerFactory.getLogger(JpaTickLogAdapter.class);

    private final JpaTickLogRepository tickLogRepository;
    private final JpaDepthSnapshotRepository depthSnapshotRepository;

    public JpaTickLogAdapter(JpaTickLogRepository tickLogRepository,
                             JpaDepthSnapshotRepository depthSnapshotRepository) {
        this.tickLogRepository = tickLogRepository;
        this.depthSnapshotRepository = depthSnapshotRepository;
    }

    @Override
    @Transactional
    public void saveTick(Instrument instrument, double price, long size,
                         double bidPrice, double askPrice,
                         String classification, String exchange, Instant timestamp) {
        var entity = new TickLogEntity(instrument, timestamp, price, size,
                                       classification, bidPrice, askPrice, exchange);
        tickLogRepository.save(entity);
    }

    @Override
    @Transactional
    public void saveDepthSnapshot(Instrument instrument,
                                  long totalBidSize, long totalAskSize, double depthImbalance,
                                  double bestBid, double bestAsk, double spread,
                                  Instant timestamp) {
        var entity = new DepthSnapshotEntity(instrument, timestamp,
                                             totalBidSize, totalAskSize, depthImbalance,
                                             bestBid, bestAsk, spread);
        depthSnapshotRepository.save(entity);
    }

    @Override
    @Transactional
    public void purgeOlderThan(Instant cutoff) {
        int ticksDeleted = tickLogRepository.deleteByTimestampBefore(cutoff);
        int depthDeleted = depthSnapshotRepository.deleteByTimestampBefore(cutoff);
        if (ticksDeleted > 0 || depthDeleted > 0) {
            log.info("Purged order flow data older than {}: {} ticks, {} depth snapshots",
                     cutoff, ticksDeleted, depthDeleted);
        }
    }
}
