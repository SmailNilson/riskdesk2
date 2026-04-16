package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorProposedTradePlan;
import com.riskdesk.domain.analysis.port.MentorSignalReviewRepositoryPort;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionEligibilityStatus;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.MentorSignalReviewRecord;
import com.riskdesk.domain.model.TradeExecutionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class ExecutionManagerService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionManagerService.class);

    /** Default entry-limit buffer: no shift — exact mentor level. */
    static final int DEFAULT_ENTRY_LIMIT_BUFFER_TICKS = 0;

    private final MentorSignalReviewRepositoryPort reviewRepository;
    private final TradeExecutionRepositoryPort tradeExecutionRepository;
    private final IbkrOrderService ibkrOrderService;
    private final ObjectMapper objectMapper;

    /**
     * Signed number of ticks by which the entry LIMIT is shifted before being
     * submitted to IBKR. Convention: positive value shifts the limit in the
     * direction of the trade (LONG → higher, SHORT → lower), which improves
     * fill probability at the cost of a slightly worse entry. Negative value
     * shifts it against the trade (more patient, better entry, less likely to
     * fill). Zero (default) submits the mentor's proposed level exactly as-is
     * — this is backward-compatible with pre-PR-14 behaviour.
     */
    private final int entryLimitBufferTicks;

    @Autowired
    public ExecutionManagerService(MentorSignalReviewRepositoryPort reviewRepository,
                                   TradeExecutionRepositoryPort tradeExecutionRepository,
                                   IbkrOrderService ibkrOrderService,
                                   ObjectMapper objectMapper,
                                   @Value("${riskdesk.execution.entry-limit-buffer-ticks:0}")
                                   int entryLimitBufferTicks) {
        this.reviewRepository = reviewRepository;
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.ibkrOrderService = ibkrOrderService;
        this.objectMapper = objectMapper;
        this.entryLimitBufferTicks = entryLimitBufferTicks;
        if (entryLimitBufferTicks != 0) {
            log.info("Execution entry-limit buffer active: {} ticks ({} fill)",
                entryLimitBufferTicks,
                entryLimitBufferTicks > 0 ? "more aggressive" : "more patient");
        }
    }

    /**
     * Test-only constructor preserving the pre-PR-14 contract — buffer defaults
     * to {@link #DEFAULT_ENTRY_LIMIT_BUFFER_TICKS} (no shift).
     */
    public ExecutionManagerService(MentorSignalReviewRepositoryPort reviewRepository,
                                   TradeExecutionRepositoryPort tradeExecutionRepository,
                                   IbkrOrderService ibkrOrderService,
                                   ObjectMapper objectMapper) {
        this(reviewRepository, tradeExecutionRepository, ibkrOrderService, objectMapper,
            DEFAULT_ENTRY_LIMIT_BUFFER_TICKS);
    }

    public TradeExecutionRecord ensureExecutionCreated(CreateExecutionCommand command) {
        if (command.mentorSignalReviewId() == null) {
            throw new IllegalArgumentException("mentorSignalReviewId is required");
        }
        if (command.brokerAccountId() == null || command.brokerAccountId().isBlank()) {
            throw new IllegalArgumentException("brokerAccountId is required");
        }
        if (command.quantity() == null || command.quantity() < 1) {
            throw new IllegalArgumentException("quantity must be >= 1");
        }
        if (command.triggerSource() == null) {
            throw new IllegalArgumentException("triggerSource is required");
        }

        Optional<TradeExecutionRecord> existing = tradeExecutionRepository.findByMentorSignalReviewId(command.mentorSignalReviewId());
        if (existing.isPresent()) {
            return enrichExistingExecution(existing.get(), command);
        }

        MentorSignalReviewRecord review = reviewRepository.findById(command.mentorSignalReviewId())
            .orElseThrow(() -> new IllegalArgumentException("mentor review not found: " + command.mentorSignalReviewId()));

        validateReviewForExecution(review);

        MentorProposedTradePlan tradePlan = extractTradePlan(review);
        Instrument instrument = Instrument.valueOf(review.getInstrument());
        Instant requestedAt = command.requestedAt() == null ? Instant.now() : command.requestedAt();

        TradeExecutionRecord candidate = new TradeExecutionRecord();
        candidate.setExecutionKey("exec:mentor-review:" + review.getId());
        candidate.setMentorSignalReviewId(review.getId());
        candidate.setReviewAlertKey(review.getAlertKey());
        candidate.setReviewRevision(review.getRevision());
        candidate.setBrokerAccountId(command.brokerAccountId());
        candidate.setInstrument(review.getInstrument());
        candidate.setTimeframe(review.getTimeframe());
        candidate.setAction(review.getAction());
        candidate.setQuantity(command.quantity());
        candidate.setTriggerSource(command.triggerSource());
        candidate.setRequestedBy(blankToNull(command.requestedBy()));
        candidate.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        candidate.setStatusReason("Execution foundation created. IBKR placement not started.");
        BigDecimal bufferedEntry = applyEntryLimitBuffer(
            BigDecimal.valueOf(tradePlan.entryPrice()), instrument, review.getAction());
        candidate.setNormalizedEntryPrice(normalizeToTick(bufferedEntry, instrument));
        candidate.setVirtualStopLoss(normalizeToTick(BigDecimal.valueOf(tradePlan.stopLoss()), instrument));
        candidate.setVirtualTakeProfit(normalizeToTick(BigDecimal.valueOf(tradePlan.takeProfit()), instrument));
        candidate.setDisasterStopPrice(null);
        candidate.setEntryOrderId(null);
        candidate.setDisasterStopOrderId(null);
        candidate.setLastReliableLivePrice(null);
        candidate.setLastReliableLivePriceAt(null);
        candidate.setCreatedAt(requestedAt);
        candidate.setUpdatedAt(requestedAt);
        candidate.setEntrySubmittedAt(null);
        candidate.setEntryFilledAt(null);
        candidate.setVirtualExitTriggeredAt(null);
        candidate.setExitSubmittedAt(null);
        candidate.setClosedAt(null);

        return tradeExecutionRepository.createIfAbsent(candidate);
    }

    @Transactional(noRollbackFor = IllegalStateException.class)
    public TradeExecutionRecord submitEntryOrder(SubmitEntryOrderCommand command) {
        if (command.executionId() == null) {
            throw new IllegalArgumentException("executionId is required");
        }

        TradeExecutionRecord execution = tradeExecutionRepository.findByIdForUpdate(command.executionId())
            .orElseThrow(() -> new IllegalArgumentException("trade execution not found: " + command.executionId()));

        if (execution.getEntryOrderId() != null && execution.getStatus() == ExecutionStatus.ENTRY_SUBMITTED) {
            return execution;
        }
        if (execution.getStatus() != ExecutionStatus.PENDING_ENTRY_SUBMISSION) {
            throw new IllegalStateException("trade execution is not pending entry submission");
        }
        if (execution.getQuantity() == null || execution.getQuantity() < 1) {
            throw new IllegalStateException("trade execution is missing quantity");
        }

        Instant requestedAt = command.requestedAt() == null ? Instant.now() : command.requestedAt();
        try {
            BrokerEntryOrderSubmission submission = ibkrOrderService.submitEntryOrder(new BrokerEntryOrderRequest(
                execution.getId(),
                execution.getExecutionKey(),
                execution.getBrokerAccountId(),
                execution.getInstrument(),
                execution.getAction(),
                execution.getQuantity(),
                execution.getNormalizedEntryPrice()
            ));

            execution.setEntryOrderId(submission.brokerOrderId());
            execution.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
            execution.setStatusReason("IBKR entry order submitted: " + submission.brokerOrderStatus());
            execution.setEntrySubmittedAt(submission.submittedAt() == null ? requestedAt : submission.submittedAt());
            execution.setUpdatedAt(submission.submittedAt() == null ? requestedAt : submission.submittedAt());
            execution.setRequestedBy(blankToNull(command.requestedBy()) == null ? execution.getRequestedBy() : blankToNull(command.requestedBy()));
            return tradeExecutionRepository.save(execution);
        } catch (RuntimeException e) {
            String errorMessage = truncate("IBKR entry submission failed: " + e.getMessage(), 256);
            execution.setStatus(ExecutionStatus.FAILED);
            execution.setStatusReason(errorMessage);
            execution.setUpdatedAt(requestedAt);
            tradeExecutionRepository.save(execution);
            throw new IllegalStateException(errorMessage, e);
        }
    }

    public Optional<TradeExecutionRecord> findByMentorSignalReviewId(Long mentorSignalReviewId) {
        if (mentorSignalReviewId == null) {
            return Optional.empty();
        }
        return tradeExecutionRepository.findByMentorSignalReviewId(mentorSignalReviewId);
    }

    public List<TradeExecutionRecord> findByMentorSignalReviewIds(Collection<Long> mentorSignalReviewIds) {
        if (mentorSignalReviewIds == null || mentorSignalReviewIds.isEmpty()) {
            return List.of();
        }
        return tradeExecutionRepository.findByMentorSignalReviewIds(mentorSignalReviewIds).stream()
            .sorted(Comparator.comparing(TradeExecutionRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
    }

    public Optional<TradeExecutionRecord> findById(Long executionId) {
        if (executionId == null) {
            return Optional.empty();
        }
        return tradeExecutionRepository.findById(executionId);
    }

    private TradeExecutionRecord enrichExistingExecution(TradeExecutionRecord existing, CreateExecutionCommand command) {
        boolean dirty = false;
        Instant requestedAt = command.requestedAt() == null ? Instant.now() : command.requestedAt();

        if (existing.getQuantity() == null && command.quantity() != null) {
            existing.setQuantity(command.quantity());
            dirty = true;
        }
        if ((existing.getRequestedBy() == null || existing.getRequestedBy().isBlank()) && blankToNull(command.requestedBy()) != null) {
            existing.setRequestedBy(blankToNull(command.requestedBy()));
            dirty = true;
        }
        if (dirty) {
            existing.setUpdatedAt(requestedAt);
            return tradeExecutionRepository.save(existing);
        }
        return existing;
    }

    private void validateReviewForExecution(MentorSignalReviewRecord review) {
        if (!"DONE".equals(review.getStatus())) {
            throw new IllegalStateException("mentor review is not completed");
        }
        if (review.getExecutionEligibilityStatus() != ExecutionEligibilityStatus.ELIGIBLE) {
            throw new IllegalStateException("mentor review is not execution-eligible");
        }
        if (review.getInstrument() == null || review.getAction() == null || review.getTimeframe() == null) {
            throw new IllegalStateException("mentor review is missing execution context");
        }
        extractTradePlan(review);
    }

    private MentorProposedTradePlan extractTradePlan(MentorSignalReviewRecord review) {
        if (review.getAnalysisJson() == null || review.getAnalysisJson().isBlank()) {
            throw new IllegalStateException("mentor review is missing structured analysis");
        }
        try {
            MentorAnalyzeResponse response = objectMapper.readValue(review.getAnalysisJson(), MentorAnalyzeResponse.class);
            if (response.analysis() == null || response.analysis().proposedTradePlan() == null) {
                throw new IllegalStateException("mentor review is missing a proposed trade plan");
            }
            MentorProposedTradePlan plan = response.analysis().proposedTradePlan();
            if (plan.entryPrice() == null || plan.stopLoss() == null || plan.takeProfit() == null) {
                throw new IllegalStateException("mentor review trade plan is incomplete");
            }
            return plan;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("mentor review analysis is unreadable", e);
        }
    }

    private BigDecimal normalizeToTick(BigDecimal price, Instrument instrument) {
        BigDecimal tickSize = instrument.getTickSize();
        return price.divide(tickSize, 0, RoundingMode.HALF_UP)
            .multiply(tickSize)
            .setScale(tickSize.scale(), RoundingMode.HALF_UP);
    }

    /**
     * Shifts the mentor-proposed entry by {@link #entryLimitBufferTicks} in the
     * signed direction of the trade before the price is tick-normalized.
     *
     * <p>Sign convention: positive buffer shifts <b>with</b> the trade direction
     * (LONG → higher limit, SHORT → lower limit), trading a slightly worse
     * entry for a higher chance of being filled. Negative buffer shifts against
     * the trade for a better entry but lower fill probability. Zero leaves the
     * mentor's level untouched.
     *
     * <p>For an unknown action label the buffer is ignored — no silent directional
     * assumption when the data is garbled. A WARN is emitted so the operator
     * notices: the order will still be submitted at the raw level rather than
     * failing, so this preserves the pre-PR-14 behaviour for malformed reviews.
     */
    BigDecimal applyEntryLimitBuffer(BigDecimal entry, Instrument instrument, String action) {
        if (entry == null || entryLimitBufferTicks == 0 || instrument == null) {
            return entry;
        }
        BigDecimal shift = instrument.getTickSize()
            .multiply(BigDecimal.valueOf(entryLimitBufferTicks));
        if ("LONG".equalsIgnoreCase(action) || "BUY".equalsIgnoreCase(action)) {
            return entry.add(shift);
        }
        if ("SHORT".equalsIgnoreCase(action) || "SELL".equalsIgnoreCase(action)) {
            return entry.subtract(shift);
        }
        log.warn("Entry-limit buffer skipped: unknown action '{}' — submitting raw mentor level {}",
            action, entry);
        return entry;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
