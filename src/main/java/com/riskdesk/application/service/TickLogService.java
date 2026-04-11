package com.riskdesk.application.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.port.TickLogPort;
import com.riskdesk.infrastructure.persistence.JpaTickLogRepository;
import com.riskdesk.infrastructure.persistence.entity.TickLogEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Batched tick logging service for UC-OF-008 (Tick Data Logger).
 *
 * <p>Ticks arrive at high frequency from the IBKR EReader thread. Instead of
 * persisting each individually (which would overwhelm PostgreSQL), ticks are
 * buffered in a lock-free ConcurrentLinkedQueue and flushed periodically
 * or when the buffer reaches a configurable size.</p>
 *
 * <p>A separate scheduled job purges data older than the retention period (default 30 days).</p>
 */
@Service
public class TickLogService {

    private static final Logger log = LoggerFactory.getLogger(TickLogService.class);

    private final TickLogPort tickLogPort;
    private final JpaTickLogRepository tickLogRepository;
    private final ConcurrentLinkedQueue<TickLogEntity> tickBuffer = new ConcurrentLinkedQueue<>();
    private final AtomicLong totalTicksLogged = new AtomicLong(0);

    private final boolean enabled;
    private final int batchSize;
    private final int retentionDays;

    public TickLogService(TickLogPort tickLogPort,
                          JpaTickLogRepository tickLogRepository,
                          @Value("${riskdesk.order-flow.tick-log.enabled:true}") boolean enabled,
                          @Value("${riskdesk.order-flow.tick-log.batch-size:100}") int batchSize,
                          @Value("${riskdesk.order-flow.tick-log.retention-days:30}") int retentionDays) {
        this.tickLogPort = tickLogPort;
        this.tickLogRepository = tickLogRepository;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.retentionDays = retentionDays;
    }

    /**
     * Buffer a tick for batched persistence. Called from the IBKR EReader thread.
     * This method is lock-free and returns immediately.
     */
    public void bufferTick(Instrument instrument, double price, long size,
                           double bidPrice, double askPrice,
                           String classification, String exchange, Instant timestamp) {
        if (!enabled) return;
        tickBuffer.add(new TickLogEntity(instrument, timestamp, price, size,
                                         classification, bidPrice, askPrice, exchange));

        // Flush immediately if buffer exceeds batch size
        if (tickBuffer.size() >= batchSize) {
            flushBuffer();
        }
    }

    /**
     * Periodic buffer flush — every 5 seconds (configurable).
     * Drains the buffer and persists all buffered ticks in a single batch.
     */
    @Scheduled(fixedDelayString = "${riskdesk.order-flow.tick-log.flush-interval-ms:5000}",
               initialDelay = 10_000)
    public void flushBuffer() {
        if (!enabled || tickBuffer.isEmpty()) return;

        List<TickLogEntity> batch = new ArrayList<>(batchSize);
        TickLogEntity tick;
        while ((tick = tickBuffer.poll()) != null && batch.size() < batchSize * 2) {
            batch.add(tick);
        }

        if (batch.isEmpty()) return;

        try {
            tickLogRepository.saveAll(batch);
            long total = totalTicksLogged.addAndGet(batch.size());
            if (total % 10_000 == 0) {
                log.info("Tick log milestone: {} total ticks persisted", total);
            }
        } catch (Exception e) {
            log.warn("Failed to persist {} ticks: {}", batch.size(), e.getMessage());
        }
    }

    /**
     * Delegate depth snapshot persistence to the port.
     * Called at lower frequency (every ~10s) so no batching needed.
     */
    public void logDepthSnapshot(Instrument instrument,
                                 long totalBidSize, long totalAskSize, double depthImbalance,
                                 double bestBid, double bestAsk, double spread) {
        if (!enabled) return;
        try {
            tickLogPort.saveDepthSnapshot(instrument, totalBidSize, totalAskSize,
                                          depthImbalance, bestBid, bestAsk, spread, Instant.now());
        } catch (Exception e) {
            log.warn("Failed to persist depth snapshot for {}: {}", instrument, e.getMessage());
        }
    }

    /**
     * Daily purge of data older than retention period.
     * Runs at 03:17 UTC to avoid peak hours and the :00/:30 minute marks.
     */
    @Scheduled(cron = "0 17 3 * * *")
    public void purgeExpiredData() {
        if (!enabled) return;
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        log.info("Purging order flow data older than {} (retention: {} days)", cutoff, retentionDays);
        try {
            tickLogPort.purgeOlderThan(cutoff);
        } catch (Exception e) {
            log.error("Failed to purge expired order flow data: {}", e.getMessage(), e);
        }
    }

    public long getTotalTicksLogged() {
        return totalTicksLogged.get();
    }

    public int getBufferSize() {
        return tickBuffer.size();
    }
}
