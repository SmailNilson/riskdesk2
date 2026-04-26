package com.riskdesk.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dedicated thread pool for the analysis snapshot fan-out so it does not
 * compete with the IBKR EWrapper threads or the Spring scheduler pool.
 * <p>
 * <b>Sizing (PR #269 round-6 review fix):</b> a single
 * {@code AnalysisSnapshotAggregator.capture()} submits 8 blocking futures
 * (indicators, smc, orderFlow context, momentum, absorption, distribution,
 * cycle, macro). The pool was previously 4 threads, so half the work queued
 * before it could start. Combined with the 3-second fan-out timeout, that
 * pushed normal-load requests into avoidable {@code StaleSnapshotException}
 * 503s.
 * <p>
 * The pool is now sized at {@link #FAN_OUT_TASKS_PER_REQUEST} × 2 = 16, which
 * accommodates one full fan-out concurrently with another in progress without
 * queueing — typical for a scheduler tick that scans 4 instruments × 2 timeframes
 * sequentially per cycle.
 */
@Configuration
public class AnalysisExecutorConfig {

    /** Tasks submitted per {@code capture()} call — keep in sync with the aggregator. */
    private static final int FAN_OUT_TASKS_PER_REQUEST = 8;

    /** Headroom factor: how many concurrent captures we want to support without queueing. */
    private static final int CONCURRENT_HEADROOM = 2;

    public static final int POOL_SIZE = FAN_OUT_TASKS_PER_REQUEST * CONCURRENT_HEADROOM;

    @Bean(name = "analysisExecutor")
    public ExecutorService analysisExecutor() {
        return Executors.newFixedThreadPool(POOL_SIZE, r -> {
            Thread t = new Thread(r);
            t.setName("analysis-snapshot-" + t.threadId());
            t.setDaemon(true);
            return t;
        });
    }
}
