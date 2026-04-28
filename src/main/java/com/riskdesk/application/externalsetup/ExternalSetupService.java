package com.riskdesk.application.externalsetup;

import com.riskdesk.application.service.ExecutionManagerService;
import com.riskdesk.application.service.OrderFlowQuickExecutionCommand;
import com.riskdesk.application.service.OrderFlowQuickExecutionService;
import com.riskdesk.application.service.SubmitEntryOrderCommand;
import com.riskdesk.domain.externalsetup.ExternalSetup;
import com.riskdesk.domain.externalsetup.ExternalSetupConfidence;
import com.riskdesk.domain.externalsetup.ExternalSetupStatus;
import com.riskdesk.domain.externalsetup.port.ExternalSetupRepositoryPort;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.config.ExternalSetupProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the lifecycle of externally-submitted setups (Claude wakeup loop → SaaS).
 *
 * <p>Submit creates a {@link ExternalSetupStatus#PENDING PENDING} setup with a per-instrument
 * TTL. Validate optionally chains into the existing
 * {@link OrderFlowQuickExecutionService order-flow quick-execution} pipeline (creates a
 * synthetic Mentor review and arms a {@link TradeExecutionRecord}), then dispatches the entry
 * order to IBKR through {@link ExecutionManagerService}.</p>
 */
@Service
public class ExternalSetupService {

    private static final Logger log = LoggerFactory.getLogger(ExternalSetupService.class);
    private static final String WS_TOPIC = "/topic/external-setups";
    private static final String DEFAULT_TIMEFRAME = "5m";

    private final ExternalSetupRepositoryPort repository;
    private final ExternalSetupProperties properties;
    private final OrderFlowQuickExecutionService quickExecutionService;
    private final ExecutionManagerService executionManagerService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    public ExternalSetupService(ExternalSetupRepositoryPort repository,
                                ExternalSetupProperties properties,
                                OrderFlowQuickExecutionService quickExecutionService,
                                ExecutionManagerService executionManagerService,
                                SimpMessagingTemplate messagingTemplate,
                                Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.quickExecutionService = quickExecutionService;
        this.executionManagerService = executionManagerService;
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    @Transactional
    public ExternalSetup submit(ExternalSetupSubmissionCommand command) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("external setup pipeline is disabled");
        }
        validateSubmission(command);

        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(properties.ttlFor(command.instrument()));

        ExternalSetup setup = ExternalSetup.forSubmission(
            "es:" + command.instrument().name() + ":" + command.direction().name() + ":" + UUID.randomUUID(),
            command.instrument(),
            command.direction(),
            command.entry(),
            command.stopLoss(),
            command.takeProfit1(),
            command.takeProfit2(),
            command.confidence(),
            command.triggerLabel(),
            command.payloadJson(),
            command.source(),
            command.sourceRef(),
            now,
            expiresAt
        );

        ExternalSetup saved = repository.save(setup);
        log.info("ExternalSetup {} submitted: {} {} entry={} sl={} tp1={} confidence={} ttl={} (key={})",
            saved.getId(),
            saved.getInstrument(),
            saved.getDirection(),
            saved.getEntry(),
            saved.getStopLoss(),
            saved.getTakeProfit1(),
            saved.getConfidence(),
            properties.ttlFor(saved.getInstrument()),
            saved.getSetupKey());

        broadcast("SETUP_NEW", saved);

        if (properties.isAutoExecuteOnHighConfidence() && saved.getConfidence() == ExternalSetupConfidence.HIGH) {
            try {
                ExternalSetup armed = autoExecute(saved);
                return armed;
            } catch (RuntimeException e) {
                log.warn("ExternalSetup {} auto-execute failed; staying PENDING for manual review: {}",
                    saved.getId(), e.getMessage());
            }
        }
        return saved;
    }

    @Transactional
    public ExternalSetup validate(ExternalSetupValidationCommand command) {
        ExternalSetup setup = repository.findById(command.setupId())
            .orElseThrow(() -> new IllegalArgumentException("setup not found: " + command.setupId()));
        if (setup.getStatus() != ExternalSetupStatus.PENDING) {
            throw new IllegalStateException("setup is not pending: status=" + setup.getStatus());
        }
        if (setup.isExpiredAt(Instant.now(clock))) {
            // race: TTL elapsed between user click and tx start. Mark expired and reject the validation.
            setup.setStatus(ExternalSetupStatus.EXPIRED);
            ExternalSetup expired = repository.save(setup);
            broadcast("SETUP_EXPIRED", expired);
            throw new IllegalStateException("setup expired before validation");
        }

        BigDecimal entryPrice = command.overrideEntryPrice() != null
            ? command.overrideEntryPrice()
            : setup.getEntry();

        int quantity = command.quantity() != null && command.quantity() > 0
            ? command.quantity()
            : properties.getDefaultQuantity();

        String brokerAccount = command.brokerAccountId() != null && !command.brokerAccountId().isBlank()
            ? command.brokerAccountId()
            : properties.getDefaultBrokerAccount();
        if (brokerAccount == null || brokerAccount.isBlank()) {
            throw new IllegalStateException("no broker account configured (riskdesk.external-setup.default-broker-account)");
        }

        TradeExecutionRecord execution = arm(setup, entryPrice, quantity, brokerAccount,
            "external-setup-validate:" + command.validatedBy());

        executionManagerService.submitEntryOrder(new SubmitEntryOrderCommand(
            execution.getId(),
            Instant.now(clock),
            command.validatedBy()
        ));

        setup.setStatus(ExternalSetupStatus.EXECUTED);
        setup.setValidatedAt(Instant.now(clock));
        setup.setValidatedBy(command.validatedBy());
        setup.setTradeExecutionId(execution.getId());
        setup.setExecutedAtPrice(entryPrice);
        setup.setAutoExecuted(false);
        ExternalSetup saved = repository.save(setup);
        broadcast("SETUP_VALIDATED", saved);
        log.info("ExternalSetup {} validated by {} → execution {}", saved.getId(), command.validatedBy(), execution.getId());
        return saved;
    }

    @Transactional
    public ExternalSetup reject(Long setupId, String rejectedBy, String reason) {
        ExternalSetup setup = repository.findById(setupId)
            .orElseThrow(() -> new IllegalArgumentException("setup not found: " + setupId));
        if (setup.getStatus() != ExternalSetupStatus.PENDING) {
            throw new IllegalStateException("setup is not pending: status=" + setup.getStatus());
        }
        setup.setStatus(ExternalSetupStatus.REJECTED);
        setup.setValidatedAt(Instant.now(clock));
        setup.setValidatedBy(rejectedBy);
        setup.setRejectionReason(reason);
        ExternalSetup saved = repository.save(setup);
        broadcast("SETUP_REJECTED", saved);
        log.info("ExternalSetup {} rejected by {} (reason={})", saved.getId(), rejectedBy, reason);
        return saved;
    }

    @Transactional
    public int expirePending() {
        Instant now = Instant.now(clock);
        List<ExternalSetup> stale = repository.findPendingExpiredAt(now, 200);
        int expired = 0;
        for (ExternalSetup setup : stale) {
            setup.setStatus(ExternalSetupStatus.EXPIRED);
            ExternalSetup saved = repository.save(setup);
            broadcast("SETUP_EXPIRED", saved);
            expired++;
        }
        if (expired > 0) {
            log.info("ExternalSetup expiry sweep: {} setups marked EXPIRED", expired);
        }
        return expired;
    }

    public List<ExternalSetup> listByStatuses(List<ExternalSetupStatus> statuses, int limit) {
        List<ExternalSetupStatus> effective = (statuses == null || statuses.isEmpty())
            ? List.of(ExternalSetupStatus.PENDING)
            : statuses;
        return repository.findByStatuses(effective, Math.min(Math.max(limit, 1), 200));
    }

    public Optional<ExternalSetup> findById(Long id) {
        return repository.findById(id);
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * @return true when the provided header matches the configured api token.
     * False if either side is blank or unset.
     */
    public boolean tokenMatches(String providedHeader) {
        String configured = properties.getApiToken();
        if (configured == null || configured.isBlank() || providedHeader == null) {
            return false;
        }
        return configured.equals(providedHeader);
    }

    public boolean isApiTokenConfigured() {
        return properties.getApiToken() != null && !properties.getApiToken().isBlank();
    }

    public int getRateLimitPerMinute() {
        return properties.getRateLimitPerMinute();
    }

    public ExternalSetupStatusView statusView() {
        Map<String, Long> ttlPerInstrument = new HashMap<>();
        properties.resolvedTtls().forEach((k, v) -> ttlPerInstrument.put(k.name(), v.toSeconds()));
        return new ExternalSetupStatusView(
            properties.isEnabled(),
            properties.isAutoExecuteOnHighConfidence(),
            properties.getDefaultTtl().toSeconds(),
            ttlPerInstrument,
            properties.getRateLimitPerMinute(),
            properties.getDefaultBrokerAccount() != null && !properties.getDefaultBrokerAccount().isBlank()
        );
    }

    // -------- internals --------

    private ExternalSetup autoExecute(ExternalSetup setup) {
        String brokerAccount = properties.getDefaultBrokerAccount();
        if (brokerAccount == null || brokerAccount.isBlank()) {
            throw new IllegalStateException("auto-execute requested but no default broker account configured");
        }
        BigDecimal entryPrice = setup.getEntry();
        int quantity = properties.getDefaultQuantity();

        TradeExecutionRecord execution = arm(setup, entryPrice, quantity, brokerAccount, "external-setup-auto-exec");
        executionManagerService.submitEntryOrder(new SubmitEntryOrderCommand(
            execution.getId(),
            Instant.now(clock),
            "external-setup-auto-exec"
        ));
        setup.setStatus(ExternalSetupStatus.EXECUTED);
        setup.setValidatedAt(Instant.now(clock));
        setup.setValidatedBy("auto-exec");
        setup.setTradeExecutionId(execution.getId());
        setup.setExecutedAtPrice(entryPrice);
        setup.setAutoExecuted(true);
        ExternalSetup saved = repository.save(setup);
        broadcast("SETUP_VALIDATED", saved);
        return saved;
    }

    /**
     * Delegates to {@link OrderFlowQuickExecutionService} to create a synthetic Mentor review
     * and a {@link TradeExecutionRecord} in {@code PENDING_ENTRY_SUBMISSION}. The execution row
     * is the bridge between this setup and the existing IBKR pipeline.
     */
    private TradeExecutionRecord arm(ExternalSetup setup, BigDecimal entryPrice, int quantity,
                                     String brokerAccount, String reasonPrefix) {
        if (!quickExecutionService.isEnabled()) {
            throw new IllegalStateException(
                "ExternalSetup validation requires riskdesk.orderflow.quick-execution.enabled=true");
        }
        return quickExecutionService.arm(new OrderFlowQuickExecutionCommand(
            setup.getInstrument().name(),
            DEFAULT_TIMEFRAME,
            setup.getDirection() == Side.LONG ? "LONG" : "SHORT",
            entryPrice.doubleValue(),
            setup.getStopLoss().doubleValue(),
            // pick TP1 by default; TP2 stays informative on the setup row.
            setup.getTakeProfit1().doubleValue(),
            quantity,
            brokerAccount,
            reasonPrefix + " — " + (setup.getTriggerLabel() == null ? "n/a" : setup.getTriggerLabel())
        ));
    }

    private void validateSubmission(ExternalSetupSubmissionCommand c) {
        if (c.instrument() == null) {
            throw new IllegalArgumentException("instrument is required");
        }
        if (!c.instrument().isExchangeTradedFuture()) {
            throw new IllegalArgumentException("instrument is not an exchange-traded future: " + c.instrument());
        }
        if (c.direction() == null) {
            throw new IllegalArgumentException("direction is required");
        }
        if (c.entry() == null || c.stopLoss() == null || c.takeProfit1() == null) {
            throw new IllegalArgumentException("entry, stopLoss and takeProfit1 are all required");
        }
        BigDecimal entry = c.entry();
        BigDecimal sl = c.stopLoss();
        BigDecimal tp1 = c.takeProfit1();
        if (c.direction() == Side.LONG) {
            if (sl.compareTo(entry) >= 0 || entry.compareTo(tp1) >= 0) {
                throw new IllegalArgumentException(
                    "LONG requires stopLoss < entry < takeProfit1 (got SL=" + sl + ", entry=" + entry + ", TP1=" + tp1 + ")");
            }
        } else {
            if (tp1.compareTo(entry) >= 0 || entry.compareTo(sl) >= 0) {
                throw new IllegalArgumentException(
                    "SHORT requires takeProfit1 < entry < stopLoss (got SL=" + sl + ", entry=" + entry + ", TP1=" + tp1 + ")");
            }
        }
        if (c.source() == null) {
            throw new IllegalArgumentException("source is required");
        }
    }

    private void broadcast(String eventType, ExternalSetup setup) {
        if (messagingTemplate == null) {
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("event", eventType);
        body.put("setup", ExternalSetupSummary.from(setup));
        try {
            messagingTemplate.convertAndSend(WS_TOPIC, body);
        } catch (RuntimeException e) {
            log.warn("ExternalSetup {} broadcast failed: {}", setup.getId(), e.getMessage());
        }
    }
}
