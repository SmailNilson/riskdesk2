package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.analysis.port.MentorSignalReviewRepositoryPort;
import com.riskdesk.domain.model.ExecutionEligibilityStatus;
import com.riskdesk.domain.model.MentorSignalReviewRecord;
import com.riskdesk.domain.model.TradeExecutionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderFlowQuickExecutionServiceTest {

    private MentorSignalReviewRepositoryPort reviewRepository;
    private ExecutionManagerService executionManagerService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        reviewRepository = mock(MentorSignalReviewRepositoryPort.class);
        executionManagerService = mock(ExecutionManagerService.class);
        objectMapper = new ObjectMapper();
    }

    private OrderFlowQuickExecutionService enabledService() {
        return new OrderFlowQuickExecutionService(reviewRepository, executionManagerService, objectMapper, true);
    }

    private OrderFlowQuickExecutionCommand mnqShort() {
        return new OrderFlowQuickExecutionCommand(
            "MNQ", "5m", "SHORT", 27245.0, 27275.0, 27170.0,
            1, "U10670585", "momentum bear x15 + OB");
    }

    private OrderFlowQuickExecutionCommand mnqLong() {
        return new OrderFlowQuickExecutionCommand(
            "MNQ", "5m", "LONG", 27140.0, 27100.0, 27250.0,
            1, "U10670585", "accumulation x24 + FVG");
    }

    @Test
    void disabled_rejectsWithIllegalState() {
        OrderFlowQuickExecutionService service = new OrderFlowQuickExecutionService(
            reviewRepository, executionManagerService, objectMapper, false);
        assertThatThrownBy(() -> service.arm(mnqShort()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("disabled");
        verifyNoInteractions(reviewRepository, executionManagerService);
    }

    @Test
    void shortSetup_createsSyntheticReviewAndExecution() {
        MentorSignalReviewRecord saved = new MentorSignalReviewRecord();
        saved.setId(42L);
        when(reviewRepository.save(any(MentorSignalReviewRecord.class))).thenReturn(saved);
        TradeExecutionRecord exec = new TradeExecutionRecord();
        exec.setId(100L);
        when(executionManagerService.ensureExecutionCreated(any())).thenReturn(exec);

        TradeExecutionRecord result = enabledService().arm(mnqShort());

        ArgumentCaptor<MentorSignalReviewRecord> reviewCaptor = ArgumentCaptor.forClass(MentorSignalReviewRecord.class);
        verify(reviewRepository).save(reviewCaptor.capture());
        MentorSignalReviewRecord review = reviewCaptor.getValue();
        assertThat(review.getInstrument()).isEqualTo("MNQ");
        assertThat(review.getAction()).isEqualTo("SHORT");
        assertThat(review.getExecutionEligibilityStatus()).isEqualTo(ExecutionEligibilityStatus.ELIGIBLE);
        assertThat(review.getAlertKey()).startsWith("orderflow:MNQ:5m:SHORT:");
        assertThat(review.getSourceType()).isEqualTo("ORDER_FLOW");
        assertThat(review.getAnalysisJson()).contains("\"entryPrice\":27245.0");
        assertThat(review.getAnalysisJson()).contains("\"stopLoss\":27275.0");
        assertThat(review.getAnalysisJson()).contains("\"takeProfit\":27170.0");

        ArgumentCaptor<CreateExecutionCommand> cmdCaptor = ArgumentCaptor.forClass(CreateExecutionCommand.class);
        verify(executionManagerService).ensureExecutionCreated(cmdCaptor.capture());
        CreateExecutionCommand cmd = cmdCaptor.getValue();
        assertThat(cmd.mentorSignalReviewId()).isEqualTo(42L);
        assertThat(cmd.brokerAccountId()).isEqualTo("U10670585");
        assertThat(cmd.quantity()).isEqualTo(1);

        assertThat(result.getId()).isEqualTo(100L);
    }

    @Test
    void longSetup_orderedCorrectly() {
        MentorSignalReviewRecord saved = new MentorSignalReviewRecord();
        saved.setId(10L);
        when(reviewRepository.save(any())).thenReturn(saved);
        when(executionManagerService.ensureExecutionCreated(any())).thenReturn(new TradeExecutionRecord());

        enabledService().arm(mnqLong());

        ArgumentCaptor<MentorSignalReviewRecord> cap = ArgumentCaptor.forClass(MentorSignalReviewRecord.class);
        verify(reviewRepository).save(cap.capture());
        assertThat(cap.getValue().getAction()).isEqualTo("LONG");
    }

    @Test
    void shortWithInvertedLevels_rejected() {
        // SHORT requires TP < entry < SL — here SL < entry, invalid
        OrderFlowQuickExecutionCommand bad = new OrderFlowQuickExecutionCommand(
            "MNQ", "5m", "SHORT", 27245.0, 27170.0, 27275.0, 1, "U10670585", "bad");
        assertThatThrownBy(() -> enabledService().arm(bad))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SHORT requires");
        verifyNoInteractions(reviewRepository, executionManagerService);
    }

    @Test
    void longWithInvertedLevels_rejected() {
        OrderFlowQuickExecutionCommand bad = new OrderFlowQuickExecutionCommand(
            "MNQ", "5m", "LONG", 27140.0, 27250.0, 27100.0, 1, "U10670585", "bad");
        assertThatThrownBy(() -> enabledService().arm(bad))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("LONG requires");
    }

    @Test
    void rewardRiskTooLow_rejected() {
        // Risk 30, reward 10 → R:R 0.33 < 0.5
        OrderFlowQuickExecutionCommand bad = new OrderFlowQuickExecutionCommand(
            "MNQ", "5m", "SHORT", 27245.0, 27275.0, 27235.0, 1, "U10670585", "thin");
        assertThatThrownBy(() -> enabledService().arm(bad))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reward:risk too low");
    }

    @Test
    void unknownInstrument_rejected() {
        OrderFlowQuickExecutionCommand bad = new OrderFlowQuickExecutionCommand(
            "FOO", "5m", "SHORT", 100.0, 110.0, 80.0, 1, "U10670585", "x");
        assertThatThrownBy(() -> enabledService().arm(bad))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown instrument");
    }

    @Test
    void invalidAction_rejected() {
        OrderFlowQuickExecutionCommand bad = new OrderFlowQuickExecutionCommand(
            "MNQ", "5m", "BUY", 27140.0, 27100.0, 27250.0, 1, "U10670585", "x");
        assertThatThrownBy(() -> enabledService().arm(bad))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("action must be LONG or SHORT");
    }

    @Test
    void synthesizedReviewHasUniqueAlertKey() {
        MentorSignalReviewRecord saved = new MentorSignalReviewRecord();
        saved.setId(1L);
        when(reviewRepository.save(any())).thenReturn(saved);
        when(executionManagerService.ensureExecutionCreated(any())).thenReturn(new TradeExecutionRecord());

        OrderFlowQuickExecutionService service = enabledService();
        service.arm(mnqShort());
        service.arm(mnqShort());

        ArgumentCaptor<MentorSignalReviewRecord> cap = ArgumentCaptor.forClass(MentorSignalReviewRecord.class);
        verify(reviewRepository, org.mockito.Mockito.times(2)).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getAlertKey())
            .isNotEqualTo(cap.getAllValues().get(1).getAlertKey());
    }
}
