package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorProposedTradePlan;
import com.riskdesk.application.dto.MentorStructuredResponse;
import com.riskdesk.domain.analysis.port.MentorSignalReviewRepositoryPort;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionEligibilityStatus;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.MentorSignalReviewRecord;
import com.riskdesk.domain.model.TradeExecutionRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionManagerServiceTest {

    @Mock
    private MentorSignalReviewRepositoryPort reviewRepository;

    @Mock
    private TradeExecutionRepositoryPort tradeExecutionRepository;

    @Mock
    private IbkrOrderService ibkrOrderService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ensureExecutionCreated_buildsPendingExecutionFromEligibleReview() throws Exception {
        ExecutionManagerService service = new ExecutionManagerService(
            reviewRepository,
            tradeExecutionRepository,
            ibkrOrderService,
            objectMapper
        );

        MentorSignalReviewRecord review = eligibleReview(77L, 2, "2026-03-28T16:00:00Z");
        when(reviewRepository.findById(77L)).thenReturn(Optional.of(review));
        when(tradeExecutionRepository.createIfAbsent(any())).thenAnswer(invocation -> {
            TradeExecutionRecord record = invocation.getArgument(0);
            record.setId(501L);
            return record;
        });

        TradeExecutionRecord created = service.ensureExecutionCreated(new CreateExecutionCommand(
            77L,
            "DU1234567",
            2,
            ExecutionTriggerSource.MANUAL_ARMING,
            Instant.parse("2026-03-28T16:01:00Z"),
            "mentor-panel"
        ));

        assertThat(created.getId()).isEqualTo(501L);

        ArgumentCaptor<TradeExecutionRecord> captor = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(tradeExecutionRepository).createIfAbsent(captor.capture());
        TradeExecutionRecord saved = captor.getValue();

        assertThat(saved.getExecutionKey()).isEqualTo("exec:mentor-review:77");
        assertThat(saved.getMentorSignalReviewId()).isEqualTo(77L);
        assertThat(saved.getReviewRevision()).isEqualTo(2);
        assertThat(saved.getBrokerAccountId()).isEqualTo("DU1234567");
        assertThat(saved.getQuantity()).isEqualTo(2);
        assertThat(saved.getStatus()).isEqualTo(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        assertThat(saved.getNormalizedEntryPrice()).isEqualByComparingTo("18123.50");
        assertThat(saved.getVirtualStopLoss()).isEqualByComparingTo("18099.75");
        assertThat(saved.getVirtualTakeProfit()).isEqualByComparingTo("18160.25");
    }

    @Test
    void ensureExecutionCreated_rejectsIneligibleReview() throws Exception {
        ExecutionManagerService service = new ExecutionManagerService(
            reviewRepository,
            tradeExecutionRepository,
            ibkrOrderService,
            objectMapper
        );

        MentorSignalReviewRecord review = eligibleReview(77L, 2, "2026-03-28T16:00:00Z");
        review.setExecutionEligibilityStatus(ExecutionEligibilityStatus.INELIGIBLE);
        when(reviewRepository.findById(77L)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service.ensureExecutionCreated(new CreateExecutionCommand(
            77L,
            "DU1234567",
            1,
            ExecutionTriggerSource.MANUAL_ARMING,
            Instant.parse("2026-03-28T16:01:00Z"),
            "mentor-panel"
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not execution-eligible");
    }

    @Test
    void ensureExecutionCreated_rejectsIncompletePlan() throws Exception {
        ExecutionManagerService service = new ExecutionManagerService(
            reviewRepository,
            tradeExecutionRepository,
            ibkrOrderService,
            objectMapper
        );

        MentorSignalReviewRecord review = eligibleReview(77L, 2, "2026-03-28T16:00:00Z");
        review.setAnalysisJson(objectMapper.writeValueAsString(new MentorAnalyzeResponse(
            9L,
            "gemini-test",
            objectMapper.readTree("{\"metadata\":{\"asset\":\"MNQ1!\"}}"),
            new MentorStructuredResponse(
                "Technical analysis",
                List.of(),
                List.of(),
                "Trade Validé - Discipline Respectée",
                ExecutionEligibilityStatus.ELIGIBLE,
                "Setup executable.",
                "Wait for confirmation.",
                new MentorProposedTradePlan(18123.38, null, 18160.24, 1.5, "Missing stop.", null, null)
            ),
            "{\"ok\":true}",
            List.of()
        )));
        when(reviewRepository.findById(77L)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service.ensureExecutionCreated(new CreateExecutionCommand(
            77L,
            "DU1234567",
            1,
            ExecutionTriggerSource.MANUAL_ARMING,
            Instant.parse("2026-03-28T16:01:00Z"),
            "mentor-panel"
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("trade plan is incomplete");
    }

    @Test
    void ensureExecutionCreated_usesReviewIdAsIdempotenceUnit() throws Exception {
        ExecutionManagerService service = new ExecutionManagerService(
            reviewRepository,
            tradeExecutionRepository,
            ibkrOrderService,
            objectMapper
        );

        MentorSignalReviewRecord reviewV1 = eligibleReview(77L, 1, "2026-03-28T16:00:00Z");
        MentorSignalReviewRecord reviewV2 = eligibleReview(78L, 2, "2026-03-28T16:00:00Z");
        reviewV2.setAlertKey(reviewV1.getAlertKey());

        when(reviewRepository.findById(77L)).thenReturn(Optional.of(reviewV1));
        when(reviewRepository.findById(78L)).thenReturn(Optional.of(reviewV2));
        when(tradeExecutionRepository.createIfAbsent(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TradeExecutionRecord first = service.ensureExecutionCreated(new CreateExecutionCommand(
            77L,
            "DU1234567",
            1,
            ExecutionTriggerSource.MANUAL_ARMING,
            Instant.parse("2026-03-28T16:01:00Z"),
            "mentor-panel"
        ));
        TradeExecutionRecord second = service.ensureExecutionCreated(new CreateExecutionCommand(
            78L,
            "DU1234567",
            1,
            ExecutionTriggerSource.MANUAL_ARMING,
            Instant.parse("2026-03-28T16:02:00Z"),
            "mentor-panel"
        ));

        assertThat(first.getExecutionKey()).isEqualTo("exec:mentor-review:77");
        assertThat(second.getExecutionKey()).isEqualTo("exec:mentor-review:78");
        assertThat(first.getMentorSignalReviewId()).isNotEqualTo(second.getMentorSignalReviewId());
    }

    @Test
    void ensureExecutionCreated_enrichesExistingExecutionWithMissingQuantity() throws Exception {
        ExecutionManagerService service = new ExecutionManagerService(
            reviewRepository,
            tradeExecutionRepository,
            ibkrOrderService,
            objectMapper
        );

        TradeExecutionRecord existing = new TradeExecutionRecord();
        existing.setId(501L);
        existing.setExecutionKey("exec:mentor-review:77");
        existing.setMentorSignalReviewId(77L);
        existing.setBrokerAccountId("DU1234567");
        existing.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        existing.setCreatedAt(Instant.parse("2026-03-28T16:00:00Z"));
        existing.setUpdatedAt(Instant.parse("2026-03-28T16:00:00Z"));

        when(tradeExecutionRepository.findByMentorSignalReviewId(77L)).thenReturn(Optional.of(existing));
        when(tradeExecutionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TradeExecutionRecord updated = service.ensureExecutionCreated(new CreateExecutionCommand(
            77L,
            "DU1234567",
            3,
            ExecutionTriggerSource.MANUAL_ARMING,
            Instant.parse("2026-03-28T16:01:00Z"),
            "mentor-panel"
        ));

        assertThat(updated.getQuantity()).isEqualTo(3);
        verify(tradeExecutionRepository).save(existing);
    }

    @Test
    void submitEntryOrder_transitionsPendingExecutionToEntrySubmitted() {
        ExecutionManagerService service = new ExecutionManagerService(
            reviewRepository,
            tradeExecutionRepository,
            ibkrOrderService,
            objectMapper
        );

        TradeExecutionRecord execution = new TradeExecutionRecord();
        execution.setId(901L);
        execution.setExecutionKey("exec:mentor-review:77");
        execution.setMentorSignalReviewId(77L);
        execution.setBrokerAccountId("DU1234567");
        execution.setInstrument("MNQ");
        execution.setAction("LONG");
        execution.setQuantity(2);
        execution.setNormalizedEntryPrice(new java.math.BigDecimal("18123.50"));
        execution.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        execution.setUpdatedAt(Instant.parse("2026-03-28T16:00:00Z"));

        when(tradeExecutionRepository.findByIdForUpdate(901L)).thenReturn(Optional.of(execution));
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(new BrokerEntryOrderSubmission(
            44001L,
            "Submitted",
            "exec:mentor-review:77",
            Instant.parse("2026-03-28T16:03:00Z")
        ));
        when(tradeExecutionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TradeExecutionRecord submitted = service.submitEntryOrder(new SubmitEntryOrderCommand(
            901L,
            Instant.parse("2026-03-28T16:03:00Z"),
            "mentor-panel"
        ));

        assertThat(submitted.getEntryOrderId()).isEqualTo(44001L);
        assertThat(submitted.getStatus()).isEqualTo(ExecutionStatus.ENTRY_SUBMITTED);
        assertThat(submitted.getEntrySubmittedAt()).isEqualTo(Instant.parse("2026-03-28T16:03:00Z"));
    }

    // ── PR-14 · Entry-limit buffer configurable ────────────────────────────

    @Test
    void entryLimitBuffer_defaultZero_unchangedFromPrePr14() {
        // Default constructor keeps the pre-PR-14 behaviour: buffer = 0.
        ExecutionManagerService service = new ExecutionManagerService(
            reviewRepository, tradeExecutionRepository, ibkrOrderService, objectMapper);

        BigDecimal raw = new BigDecimal("18123.38");
        BigDecimal shifted = service.applyEntryLimitBuffer(raw, Instrument.MNQ, "LONG");

        assertThat(shifted).isEqualByComparingTo(raw);
    }

    @Test
    void entryLimitBuffer_positiveShiftsLongHigher() {
        // Positive buffer = trade-direction shift.
        // MNQ tick size is 0.25 → +2 ticks on LONG = +0.50 above mentor level.
        ExecutionManagerService service = new ExecutionManagerService(
            reviewRepository, tradeExecutionRepository, ibkrOrderService, objectMapper, 2);

        BigDecimal shifted = service.applyEntryLimitBuffer(
            new BigDecimal("18123.38"), Instrument.MNQ, "LONG");

        assertThat(shifted).isEqualByComparingTo(new BigDecimal("18123.88"));
    }

    @Test
    void entryLimitBuffer_positiveShiftsShortLower() {
        ExecutionManagerService service = new ExecutionManagerService(
            reviewRepository, tradeExecutionRepository, ibkrOrderService, objectMapper, 2);

        BigDecimal shifted = service.applyEntryLimitBuffer(
            new BigDecimal("18123.38"), Instrument.MNQ, "SHORT");

        assertThat(shifted).isEqualByComparingTo(new BigDecimal("18122.88"));
    }

    @Test
    void entryLimitBuffer_negativeShiftsLongLower_moreConservativeFill() {
        // Negative buffer = against-direction shift for better entry, less likely fill.
        ExecutionManagerService service = new ExecutionManagerService(
            reviewRepository, tradeExecutionRepository, ibkrOrderService, objectMapper, -2);

        BigDecimal shifted = service.applyEntryLimitBuffer(
            new BigDecimal("18123.38"), Instrument.MNQ, "LONG");

        assertThat(shifted).isEqualByComparingTo(new BigDecimal("18122.88"));
    }

    @Test
    void entryLimitBuffer_acceptsBuyAndSellSynonyms() {
        ExecutionManagerService service = new ExecutionManagerService(
            reviewRepository, tradeExecutionRepository, ibkrOrderService, objectMapper, 1);

        BigDecimal buy = service.applyEntryLimitBuffer(
            new BigDecimal("100.00"), Instrument.MCL, "BUY");
        BigDecimal sell = service.applyEntryLimitBuffer(
            new BigDecimal("100.00"), Instrument.MCL, "SELL");

        // MCL tick size is 0.01 → +1 tick
        assertThat(buy).isEqualByComparingTo(new BigDecimal("100.01"));
        assertThat(sell).isEqualByComparingTo(new BigDecimal("99.99"));
    }

    @Test
    void entryLimitBuffer_unknownAction_returnsRawEntry_noSilentGuess() {
        // Guarantee: we never invent a direction when the label is garbled.
        // Returning the raw entry is safe — pre-PR-14 behaviour for that field.
        ExecutionManagerService service = new ExecutionManagerService(
            reviewRepository, tradeExecutionRepository, ibkrOrderService, objectMapper, 5);

        BigDecimal raw = new BigDecimal("18123.38");
        BigDecimal result = service.applyEntryLimitBuffer(raw, Instrument.MNQ, "???");

        assertThat(result).isEqualByComparingTo(raw);
    }

    @Test
    void entryLimitBuffer_nullEntry_returnsNull_noNpe() {
        ExecutionManagerService service = new ExecutionManagerService(
            reviewRepository, tradeExecutionRepository, ibkrOrderService, objectMapper, 2);

        assertThat(service.applyEntryLimitBuffer(null, Instrument.MNQ, "LONG")).isNull();
    }

    @Test
    void entryLimitBuffer_nullInstrument_returnsRawEntry_noNpe() {
        // If instrument is unresolved upstream, we can't derive tick size; fall
        // back to the raw entry rather than throwing. Callers will still invoke
        // tick normalization using whatever instrument they have.
        ExecutionManagerService service = new ExecutionManagerService(
            reviewRepository, tradeExecutionRepository, ibkrOrderService, objectMapper, 2);

        BigDecimal raw = new BigDecimal("18123.38");
        assertThat(service.applyEntryLimitBuffer(raw, null, "LONG")).isEqualByComparingTo(raw);
    }

    @Test
    void ensureExecutionCreated_appliesConfiguredBufferToPersistedEntry() throws Exception {
        // End-to-end: a buffer of +2 ticks on MNQ LONG shifts the mentor's
        // 18123.38 → 18123.88 → tick-normalized to 18123.88 (already aligned to 0.25).
        ExecutionManagerService service = new ExecutionManagerService(
            reviewRepository, tradeExecutionRepository, ibkrOrderService, objectMapper, 2);

        MentorSignalReviewRecord review = eligibleReview(77L, 1, "2026-03-28T16:00:00Z");
        when(reviewRepository.findById(77L)).thenReturn(Optional.of(review));
        when(tradeExecutionRepository.createIfAbsent(any())).thenAnswer(i -> {
            TradeExecutionRecord r = i.getArgument(0);
            r.setId(999L);
            return r;
        });

        service.ensureExecutionCreated(new CreateExecutionCommand(
            77L, "DU1234567", 1, ExecutionTriggerSource.MANUAL_ARMING,
            Instant.parse("2026-03-28T16:01:00Z"), "mentor-panel"));

        ArgumentCaptor<TradeExecutionRecord> captor = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(tradeExecutionRepository).createIfAbsent(captor.capture());
        // Raw mentor entry 18123.38 + 0.50 (2 × 0.25) = 18123.88 → tick-aligned to 18123.75 (HALF_UP)
        // Note: normalizeToTick rounds to nearest tick; 18123.88 rounds to 18123.75 then +0.25 → let's verify
        // 18123.88 / 0.25 = 72495.52 → HALF_UP → 72496 × 0.25 = 18124.00
        assertThat(captor.getValue().getNormalizedEntryPrice()).isEqualByComparingTo("18124.00");
        // SL and TP are unchanged by the buffer — only entry is shifted.
        assertThat(captor.getValue().getVirtualStopLoss()).isEqualByComparingTo("18099.75");
        assertThat(captor.getValue().getVirtualTakeProfit()).isEqualByComparingTo("18160.25");
    }

    private MentorSignalReviewRecord eligibleReview(Long id, int revision, String createdAt) throws Exception {
        MentorSignalReviewRecord review = new MentorSignalReviewRecord();
        review.setId(id);
        review.setAlertKey("2026-03-28T16:00:00Z:MNQ:SMC:CHoCH");
        review.setRevision(revision);
        review.setStatus("DONE");
        review.setInstrument("MNQ");
        review.setTimeframe("10m");
        review.setAction("LONG");
        review.setExecutionEligibilityStatus(ExecutionEligibilityStatus.ELIGIBLE);
        review.setExecutionEligibilityReason("Review explicitly eligible.");
        review.setAnalysisJson(objectMapper.writeValueAsString(new MentorAnalyzeResponse(
            9L,
            "gemini-test",
            objectMapper.readTree("{\"metadata\":{\"asset\":\"MNQ1!\"}}"),
            new MentorStructuredResponse(
                "Technical analysis",
                List.of(),
                List.of(),
                "Trade Validé - Discipline Respectée",
                ExecutionEligibilityStatus.ELIGIBLE,
                "Setup executable.",
                "Wait for confirmation.",
                new MentorProposedTradePlan(18123.38, 18099.81, 18160.24, 1.5, "Structured plan.", null, null)
            ),
            "{\"ok\":true}",
            List.of()
        )));
        review.setCreatedAt(Instant.parse(createdAt));
        return review;
    }
}
