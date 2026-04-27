package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorAlternativeEntry;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorProposedTradePlan;
import com.riskdesk.application.dto.MentorStructuredResponse;
import com.riskdesk.domain.analysis.port.MentorSignalReviewRepositoryPort;
import com.riskdesk.domain.model.ExecutionEligibilityStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.MentorSignalReviewRecord;
import com.riskdesk.domain.model.TradeExecutionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Arms a trade directly from an order-flow signal, bypassing the Mentor (Gemini)
 * review step. The service creates a synthetic {@link MentorSignalReviewRecord}
 * carrying the supplied trade plan, marks it {@code ELIGIBLE}, and delegates to
 * {@link ExecutionManagerService} so the existing pipeline (size validation,
 * tick normalization, idempotence, IBKR submission) is reused unchanged.
 * <p>
 * <b>No IBKR order is sent by this service.</b> The execution row is created in
 * state {@code PENDING_ENTRY_SUBMISSION}; the order is only dispatched when the
 * user calls {@code POST /api/mentor/executions/{id}/submit-entry}.
 * <p>
 * Gated by {@code riskdesk.orderflow.quick-execution.enabled} (default false).
 */
@Service
public class OrderFlowQuickExecutionService {

    private static final Logger log = LoggerFactory.getLogger(OrderFlowQuickExecutionService.class);
    private static final String SYNTHETIC_CATEGORY = "ORDER_FLOW_QUICK";
    private static final String SYNTHETIC_SOURCE_TYPE = "ORDER_FLOW";
    private static final String REQUESTED_BY = "orderflow-quick-exec";

    private final MentorSignalReviewRepositoryPort reviewRepository;
    private final ExecutionManagerService executionManagerService;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public OrderFlowQuickExecutionService(
            MentorSignalReviewRepositoryPort reviewRepository,
            ExecutionManagerService executionManagerService,
            ObjectMapper objectMapper,
            @Value("${riskdesk.orderflow.quick-execution.enabled:false}") boolean enabled) {
        this.reviewRepository = reviewRepository;
        this.executionManagerService = executionManagerService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Arms a trade from a raw order-flow signal. Creates the synthetic review +
     * execution row atomically from the caller's point of view. Throws on any
     * validation failure; the caller translates to HTTP.
     */
    public TradeExecutionRecord arm(OrderFlowQuickExecutionCommand command) {
        if (!enabled) {
            throw new IllegalStateException("orderflow quick-execution is disabled (riskdesk.orderflow.quick-execution.enabled=false)");
        }
        validate(command);

        MentorSignalReviewRecord synthetic = createSyntheticReview(command);
        MentorSignalReviewRecord saved = reviewRepository.save(synthetic);
        log.info("OrderFlow quick-execution: synthetic review {} created for {} {} {} @{} (reason: {})",
                 saved.getId(), saved.getInstrument(), saved.getAction(),
                 saved.getTimeframe(), command.entryPrice(), command.reason());

        TradeExecutionRecord execution = executionManagerService.ensureExecutionCreated(
            new CreateExecutionCommand(
                saved.getId(),
                command.brokerAccountId(),
                command.quantity(),
                ExecutionTriggerSource.MANUAL_ARMING,
                Instant.now(),
                REQUESTED_BY
            ));
        log.info("OrderFlow quick-execution: execution {} armed for review {}",
                 execution.getId(), saved.getId());
        return execution;
    }

    private void validate(OrderFlowQuickExecutionCommand command) {
        Instrument instrument;
        try {
            instrument = Instrument.valueOf(command.instrument());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown instrument: " + command.instrument(), e);
        }
        if (!instrument.isExchangeTradedFuture()) {
            throw new IllegalArgumentException("instrument is not an exchange-traded future: " + instrument);
        }

        // Directional consistency: LONG needs SL < entry < TP, SHORT the mirror.
        double entry = command.entryPrice();
        double sl = command.stopLoss();
        double tp = command.takeProfit();
        if ("LONG".equals(command.action())) {
            if (!(sl < entry && entry < tp)) {
                throw new IllegalArgumentException(
                    "LONG requires stopLoss < entryPrice < takeProfit (got SL=" + sl + ", entry=" + entry + ", TP=" + tp + ")");
            }
        } else if ("SHORT".equals(command.action())) {
            if (!(tp < entry && entry < sl)) {
                throw new IllegalArgumentException(
                    "SHORT requires takeProfit < entryPrice < stopLoss (got SL=" + sl + ", entry=" + entry + ", TP=" + tp + ")");
            }
        } else {
            throw new IllegalArgumentException("action must be LONG or SHORT (got " + command.action() + ")");
        }

        // Basic sanity on R:R — refuse setups worse than 1:0.5 (can change via config later).
        double risk = Math.abs(entry - sl);
        double reward = Math.abs(tp - entry);
        if (risk <= 0.0) {
            throw new IllegalArgumentException("entry and stopLoss must differ");
        }
        double rr = reward / risk;
        if (rr < 0.5) {
            throw new IllegalArgumentException("reward:risk too low (" + rr + " < 0.5)");
        }
    }

    private MentorSignalReviewRecord createSyntheticReview(OrderFlowQuickExecutionCommand command) {
        MentorSignalReviewRecord review = new MentorSignalReviewRecord();
        String alertKey = "orderflow:" + command.instrument() + ":" + command.timeframe()
            + ":" + command.action() + ":" + UUID.randomUUID();
        Instant now = Instant.now();
        review.setAlertKey(alertKey);
        review.setRevision(1);
        review.setTriggerType("INITIAL");
        review.setStatus("DONE");
        review.setSeverity("INFO");
        review.setCategory(SYNTHETIC_CATEGORY);
        review.setMessage("Order-flow quick-execution: " + command.reason());
        review.setInstrument(command.instrument());
        review.setTimeframe(command.timeframe());
        review.setAction(command.action());
        review.setAlertTimestamp(now);
        review.setCreatedAt(now);
        review.setCompletedAt(now);
        review.setVerdict("ELIGIBLE");
        review.setExecutionEligibilityStatus(ExecutionEligibilityStatus.ELIGIBLE);
        review.setExecutionEligibilityReason("Order-flow quick-execution bypass");
        review.setSourceType(SYNTHETIC_SOURCE_TYPE);
        review.setTriggerPrice(java.math.BigDecimal.valueOf(command.entryPrice()));
        review.setAnalysisJson(buildAnalysisJson(command));
        review.setSnapshotJson("{}");
        return review;
    }

    private String buildAnalysisJson(OrderFlowQuickExecutionCommand command) {
        double risk = Math.abs(command.entryPrice() - command.stopLoss());
        double reward = Math.abs(command.takeProfit() - command.entryPrice());
        double rr = risk > 0 ? reward / risk : 0.0;

        MentorProposedTradePlan plan = new MentorProposedTradePlan(
            command.entryPrice(),
            command.stopLoss(),
            command.takeProfit(),
            rr,
            "Order-flow signal — " + command.reason(),
            (MentorAlternativeEntry) null,
            "ORDER_FLOW"
        );
        MentorStructuredResponse structured = new MentorStructuredResponse(
            "Order-flow quick-execution (Mentor bypass).",
            List.of("Order-flow signal: " + command.reason()),
            List.of(),
            "TRADE OK - Order Flow",
            ExecutionEligibilityStatus.ELIGIBLE,
            "Order-flow quick-execution bypass",
            null,
            plan,
            null
        );
        MentorAnalyzeResponse response = new MentorAnalyzeResponse(
            null,
            "orderflow-bypass",
            null,
            structured,
            null,
            List.of()
        );
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize synthetic analysis", e);
        }
    }
}
