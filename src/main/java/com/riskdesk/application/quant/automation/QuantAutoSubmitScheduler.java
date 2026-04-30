package com.riskdesk.application.quant.automation;

import com.riskdesk.application.service.ExecutionManagerService;
import com.riskdesk.application.service.SubmitEntryOrderCommand;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.automation.AutoArmStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Polls every second for {@link ExecutionTriggerSource#QUANT_AUTO_ARM}
 * executions whose cancel window has elapsed and forwards them to
 * {@link ExecutionManagerService#submitEntryOrder} so the IBKR limit order
 * goes out.
 *
 * <p>Loop body is wrapped in a try/catch so a single bad row never stops
 * future ticks. The feature flag {@code riskdesk.quant.auto-submit.enabled}
 * is checked on every tick (not at boot) so toggling it via Spring Cloud
 * Config / environment hot-reload takes effect at the next tick.</p>
 *
 * <p>The scheduler is idempotent: once
 * {@link ExecutionManagerService#submitEntryOrder} flips the status to
 * {@code ENTRY_SUBMITTED} the row is no longer returned by
 * {@code findPendingByTriggerSource} so the same arm cannot be submitted
 * twice.</p>
 */
@Component
public class QuantAutoSubmitScheduler {

    private static final Logger log = LoggerFactory.getLogger(QuantAutoSubmitScheduler.class);

    private final TradeExecutionRepositoryPort tradeExecutionRepository;
    private final ExecutionManagerService executionManager;
    private final QuantAutoSubmitProperties props;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public QuantAutoSubmitScheduler(TradeExecutionRepositoryPort tradeExecutionRepository,
                                    ExecutionManagerService executionManager,
                                    QuantAutoSubmitProperties props,
                                    ApplicationEventPublisher eventPublisher,
                                    Clock clock) {
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.executionManager = executionManager;
        this.props = props;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Scheduled(fixedRateString = "${riskdesk.quant.auto-submit.tick-millis:1000}")
    public void tick() {
        try {
            tickInternal();
        } catch (RuntimeException e) {
            log.warn("auto-submit scheduler tick failed: {}", e.toString());
        }
    }

    /** Package-private so tests can drive a single iteration without a real scheduler. */
    void tickInternal() {
        if (!props.isEnabled()) return;

        List<TradeExecutionRecord> pending =
            tradeExecutionRepository.findPendingByTriggerSource(ExecutionTriggerSource.QUANT_AUTO_ARM);
        if (pending.isEmpty()) return;

        Instant now = clock.instant();
        long delaySeconds = Math.max(0, props.getDelaySeconds());
        for (TradeExecutionRecord exec : pending) {
            try {
                if (exec.getCreatedAt() == null) continue;
                if (now.isBefore(exec.getCreatedAt().plusSeconds(delaySeconds))) continue;

                log.info("auto-submit firing executionId={} instrument={} action={} ageSec={}",
                    exec.getId(), exec.getInstrument(), exec.getAction(),
                    java.time.Duration.between(exec.getCreatedAt(), now).getSeconds());

                executionManager.submitEntryOrder(new SubmitEntryOrderCommand(
                    exec.getId(),
                    now,
                    "quant-auto-submit"
                ));

                Instrument instrument = parseInstrument(exec.getInstrument());
                if (instrument != null) {
                    eventPublisher.publishEvent(new AutoArmStateChangedEvent(
                        instrument, exec.getId(),
                        AutoArmStateChangedEvent.State.AUTO_SUBMITTED,
                        "Auto-submitted after " + delaySeconds + "s cancel window",
                        now));
                }
            } catch (RuntimeException e) {
                log.warn("auto-submit failed executionId={}: {}", exec.getId(), e.toString());
                // Do not rethrow — keep processing the rest of the pending batch.
            }
        }
    }

    private static Instrument parseInstrument(String name) {
        try {
            return name == null ? null : Instrument.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
