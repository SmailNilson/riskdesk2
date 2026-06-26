package com.riskdesk.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Backs {@code @Scheduled} with a small thread pool instead of Spring's default single thread.
 *
 * <p>Several schedulers perform blocking broker I/O inline — {@code QuantAutoSubmitScheduler},
 * {@code ReverseDeferredOpenScheduler}, {@code StaleCloseReconciler},
 * {@code PlaybookEntryInvalidationWatcher} — and an IBKR call can block for up to the order timeout
 * (~15s). On a single scheduler thread one such stall freezes <em>every</em> other scheduled job
 * (entry submission, close reconciliation, price-driven cancels). A small pool lets independent jobs
 * proceed while one is blocked.</p>
 *
 * <p>Each individual {@code @Scheduled} task still never overlaps itself ({@code fixedDelay}
 * guarantees this regardless of pool size); the pool only allows <em>different</em> jobs to run
 * concurrently. The execution data layer already tolerates this — the IBKR EReader callback thread
 * ({@code ExecutionFillTrackingService}) mutates the same rows concurrently with every scheduler
 * today, guarded by {@code @Version} optimistic locking, {@code findByIdForUpdate}, and idempotent
 * {@code createIfAbsent}.</p>
 *
 * <p>Bound only to {@code @Scheduled} (via {@link SchedulingConfigurer}); WebSocket/STOMP heartbeats
 * and {@code @Async} keep their own executors. Set {@code riskdesk.scheduling.pool-size=1} to restore
 * the old single-threaded behaviour without a code change.</p>
 */
@Configuration
public class TaskSchedulingConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(TaskSchedulingConfig.class);

    private final int poolSize;

    public TaskSchedulingConfig(@Value("${riskdesk.scheduling.pool-size:4}") int poolSize) {
        this.poolSize = Math.max(1, poolSize);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("riskdesk-sched-");
        scheduler.setRemoveOnCancelPolicy(true);
        // Let an in-flight broker op finish on shutdown, but never hang the JVM waiting on it.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        // Observability only: Spring already wraps repeating tasks with LOG_AND_SUPPRESS so a throw
        // never cancels a fixedDelay task. Installing our own handler keeps that suppress-and-continue
        // semantics (we MUST NOT rethrow — rethrow would convert suppress→propagate and actually
        // cancel the task) while logging at ERROR so a recurring scheduler failure is visible.
        scheduler.setErrorHandler(t ->
            log.error("Scheduled task threw (suppressed, task will continue): {}", t.toString(), t));
        scheduler.initialize();
        registrar.setTaskScheduler(scheduler);
        log.info("@Scheduled task pool size = {}", poolSize);
    }
}
