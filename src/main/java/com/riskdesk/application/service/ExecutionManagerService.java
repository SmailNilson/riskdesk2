package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorProposedTradePlan;
import com.riskdesk.domain.analysis.port.MentorSignalReviewRepositoryPort;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionEligibilityStatus;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.MentorSignalReviewRecord;
import com.riskdesk.domain.model.TradeExecutionRecord;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Service
public class ExecutionManagerService {

    private final MentorSignalReviewRepositoryPort reviewRepository;
    private final TradeExecutionRepositoryPort tradeExecutionRepository;
    private final ObjectMapper objectMapper;

    public ExecutionManagerService(MentorSignalReviewRepositoryPort reviewRepository,
                                   TradeExecutionRepositoryPort tradeExecutionRepository,
                                   ObjectMapper objectMapper) {
        this.reviewRepository = reviewRepository;
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.objectMapper = objectMapper;
    }

    public TradeExecutionRecord ensureExecutionCreated(CreateExecutionCommand command) {
        if (command.mentorSignalReviewId() == null) {
            throw new IllegalArgumentException("mentorSignalReviewId is required");
        }
        if (command.brokerAccountId() == null || command.brokerAccountId().isBlank()) {
            throw new IllegalArgumentException("brokerAccountId is required");
        }
        if (command.triggerSource() == null) {
            throw new IllegalArgumentException("triggerSource is required");
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
        candidate.setTriggerSource(command.triggerSource());
        candidate.setRequestedBy(blankToNull(command.requestedBy()));
        candidate.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        candidate.setStatusReason("Execution foundation created. IBKR placement not started.");
        candidate.setNormalizedEntryPrice(normalizeToTick(BigDecimal.valueOf(tradePlan.entryPrice()), instrument));
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
