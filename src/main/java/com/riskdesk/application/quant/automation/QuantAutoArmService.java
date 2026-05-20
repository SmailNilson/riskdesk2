package com.riskdesk.application.quant.automation;

import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.automation.AutoArmConfig;
import com.riskdesk.domain.quant.automation.AutoArmDecision;
import com.riskdesk.domain.quant.automation.AutoArmDirection;
import com.riskdesk.domain.quant.automation.AutoArmEvaluator;
import com.riskdesk.domain.quant.automation.AutoArmFiredEvent;
import com.riskdesk.domain.quant.automation.AutoArmStateChangedEvent;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Application-layer entry point for the auto-arm pipeline.
 *
 * <p>{@link QuantAutoArmListener} (or any caller) invokes
 * {@link #onSnapshot(Instrument, QuantSnapshot)} after each quant scan. When
 * the snapshot qualifies, this service:
 * <ol>
 *   <li>creates a {@code TradeExecutionRecord} with status
 *       {@code PENDING_ENTRY_SUBMISSION} and {@code triggerSource =
 *       QUANT_AUTO_ARM} (no IBKR side effect — submission happens later via
 *       {@link QuantAutoSubmitScheduler} or the manual fire endpoint);</li>
 *   <li>publishes an {@link AutoArmFiredEvent} so the WebSocket adapter can
 *       broadcast the new arm to the dashboard (whether or not auto-submit is
 *       enabled — the UI must always know).</li>
 * </ol>
 *
 * <p>Cooldown is tracked in-process per-instrument: the oldest restriction
 * survives only until JVM restart, which is acceptable for a feature that
 * defaults OFF and serves humans (post-restart you'll just get one extra
 * arm).</p>
 */
@Service
public class QuantAutoArmService {

    private static final Logger log = LoggerFactory.getLogger(QuantAutoArmService.class);

    private final TradeExecutionRepositoryPort tradeExecutionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final QuantAutoArmProperties autoArmProps;
    private final QuantAutoSubmitProperties autoSubmitProps;
    private final AutoArmEvaluator evaluator;
    private final Clock clock;

    /** In-process cooldown tracker keyed by instrument. */
    private final Map<Instrument, Instant> lastArmAt = new EnumMap<>(Instrument.class);

    public QuantAutoArmService(TradeExecutionRepositoryPort tradeExecutionRepository,
                               ApplicationEventPublisher eventPublisher,
                               QuantAutoArmProperties autoArmProps,
                               QuantAutoSubmitProperties autoSubmitProps,
                               Clock clock) {
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.eventPublisher = eventPublisher;
        this.autoArmProps = autoArmProps;
        this.autoSubmitProps = autoSubmitProps;
        this.evaluator = new AutoArmEvaluator();
        this.clock = clock;
    }

    /**
     * Hook invoked after every quant scan. No-op when {@code enabled=false}.
     */
    public Optional<TradeExecutionRecord> onSnapshot(Instrument instrument, QuantSnapshot snapshot) {
        if (!autoArmProps.isEnabled()) return Optional.empty();
        if (instrument == null || snapshot == null) return Optional.empty();

        AutoArmConfig cfg = autoArmProps.toConfig(autoSubmitProps.getDelaySeconds());
        Instant now = clock.instant();
        Optional<TradeExecutionRecord> active = tradeExecutionRepository.findActiveByInstrument(instrument.name());
        Instant lastArm = lastArmAt.get(instrument);

        Optional<AutoArmDecision> decisionOpt = evaluator.evaluate(snapshot, cfg, active, lastArm, now);
        if (decisionOpt.isEmpty()) return Optional.empty();

        AutoArmDecision decision = decisionOpt.get();
        TradeExecutionRecord persisted;
        try {
            persisted = createExecution(instrument, decision);
        } catch (RuntimeException e) {
            log.warn("auto-arm persistence failed instrument={} direction={}: {}",
                instrument, decision.direction(), e.toString());
            return Optional.empty();
        }

        // Mark cooldown only after successful persistence so a transient DB
        // failure doesn't lock us out of arming for cooldownSeconds.
        lastArmAt.put(instrument, now);

        Instant autoSubmitAt = autoSubmitProps.isEnabled()
            ? now.plusSeconds(autoSubmitProps.getDelaySeconds())
            : null;

        AutoArmFiredEvent event = new AutoArmFiredEvent(
            instrument,
            persisted.getId(),
            decision.direction(),
            persisted.getNormalizedEntryPrice(),
            persisted.getVirtualStopLoss(),
            persisted.getVirtualTakeProfit(),
            decision.takeProfit2(),
            decision.sizePercent(),
            persisted.getCreatedAt(),
            decision.expiresAt(),
            autoSubmitAt,
            decision.reasoning()
        );
        eventPublisher.publishEvent(event);
        eventPublisher.publishEvent(new AutoArmStateChangedEvent(
            instrument, persisted.getId(),
            AutoArmStateChangedEvent.State.ARMED,
            decision.reasoning(), now));

        log.info("auto-arm fired instrument={} direction={} executionId={} entry={} sl={} tp1={} expiresAt={} autoSubmitAt={}",
            instrument, decision.direction(), persisted.getId(),
            persisted.getNormalizedEntryPrice(), persisted.getVirtualStopLoss(),
            persisted.getVirtualTakeProfit(), decision.expiresAt(), autoSubmitAt);
        return Optional.of(persisted);
    }

    /**
     * Cancel an armed execution. Idempotent: cancelling an already-cancelled
     * arm is a no-op (returns the existing row).
     */
    public Optional<TradeExecutionRecord> cancel(Long executionId, String requestedBy) {
        if (executionId == null) return Optional.empty();
        Optional<TradeExecutionRecord> opt = tradeExecutionRepository.findById(executionId);
        if (opt.isEmpty()) return Optional.empty();

        TradeExecutionRecord exec = opt.get();
        if (exec.getStatus() == ExecutionStatus.CANCELLED) return Optional.of(exec);
        if (exec.getStatus() != ExecutionStatus.PENDING_ENTRY_SUBMISSION) {
            // Cannot cancel — the order has already been submitted to the broker.
            return Optional.of(exec);
        }

        Instant now = clock.instant();
        exec.setStatus(ExecutionStatus.CANCELLED);
        exec.setStatusReason("Cancelled by " + (requestedBy == null || requestedBy.isBlank() ? "operator" : requestedBy));
        exec.setUpdatedAt(now);
        TradeExecutionRecord saved = tradeExecutionRepository.save(exec);

        Instrument instrument = parseInstrument(saved.getInstrument());
        if (instrument != null) {
            eventPublisher.publishEvent(new AutoArmStateChangedEvent(
                instrument, saved.getId(),
                AutoArmStateChangedEvent.State.CANCELLED,
                saved.getStatusReason(), now));
        }
        log.info("auto-arm cancelled executionId={} instrument={} by={}", saved.getId(), saved.getInstrument(), requestedBy);
        return Optional.of(saved);
    }

    /**
     * Compute the auto-submit timestamp for an existing armed execution. Used
     * by the active-arms list endpoint.
     */
    public Instant computeAutoSubmitAt(TradeExecutionRecord exec) {
        if (exec == null || !autoSubmitProps.isEnabled()) return null;
        if (exec.getCreatedAt() == null) return null;
        return exec.getCreatedAt().plusSeconds(autoSubmitProps.getDelaySeconds());
    }

    private TradeExecutionRecord createExecution(Instrument instrument, AutoArmDecision decision) {
        TradeExecutionRecord rec = new TradeExecutionRecord();
        // Synthetic, unique key per arm — the Postgres unique index on
        // executionKey de-duplicates, while mentorSignalReviewId stays null.
        String execKey = "exec:quant-auto:" + instrument.name() + ":" + decision.direction().name() + ":" + decision.decisionAt().toEpochMilli();
        rec.setExecutionKey(execKey);
        rec.setMentorSignalReviewId(null);
        rec.setReviewAlertKey(null);
        rec.setReviewRevision(null);
        String brokerAccount = autoArmProps.getBrokerAccountId();
        if (brokerAccount == null || brokerAccount.isBlank()) {
            throw new IllegalStateException("riskdesk.quant.auto-arm.broker-account-id is required when auto-arm is enabled");
        }
        rec.setBrokerAccountId(brokerAccount);
        rec.setInstrument(instrument.name());
        rec.setTimeframe("5m");
        rec.setAction(decision.direction().action());
        rec.setQuantity(autoArmProps.getDefaultQuantity());
        rec.setTriggerSource(ExecutionTriggerSource.QUANT_AUTO_ARM);
        rec.setRequestedBy("quant-auto");
        rec.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        rec.setStatusReason("Auto-armed by quant pipeline. " + decision.reasoning());
        rec.setNormalizedEntryPrice(normalize(decision.entryPrice(), instrument));
        rec.setVirtualStopLoss(normalize(decision.stopLoss(), instrument));
        rec.setVirtualTakeProfit(normalize(decision.takeProfit1(), instrument));
        rec.setCreatedAt(decision.decisionAt());
        rec.setUpdatedAt(decision.decisionAt());
        return tradeExecutionRepository.createIfAbsent(rec);
    }

    private static BigDecimal normalize(BigDecimal price, Instrument instrument) {
        if (price == null) return null;
        BigDecimal tick = instrument.getTickSize();
        return price.divide(tick, 0, RoundingMode.HALF_UP)
            .multiply(tick)
            .setScale(tick.scale(), RoundingMode.HALF_UP);
    }

    private static Instrument parseInstrument(String name) {
        try {
            return name == null ? null : Instrument.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Test-only hook to seed cooldown state. */
    void primeCooldown(Instrument instrument, Instant at) {
        if (instrument == null || at == null) return;
        lastArmAt.put(instrument, at);
    }

    /** Test-only hook for asserting cooldown was recorded. */
    Instant lastArmAt(Instrument instrument) {
        return lastArmAt.get(instrument);
    }
}
