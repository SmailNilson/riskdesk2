package com.riskdesk.externalsetup;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.application.externalsetup.ExternalSetupService;
import com.riskdesk.application.externalsetup.ExternalSetupSubmissionCommand;
import com.riskdesk.application.externalsetup.ExternalSetupValidationCommand;
import com.riskdesk.application.service.ExecutionManagerService;
import com.riskdesk.application.service.IndicatorService;
import com.riskdesk.application.service.OrderFlowQuickExecutionCommand;
import com.riskdesk.application.service.OrderFlowQuickExecutionService;
import com.riskdesk.application.service.SubmitEntryOrderCommand;
import com.riskdesk.domain.externalsetup.ExternalSetup;
import com.riskdesk.domain.externalsetup.ExternalSetupConfidence;
import com.riskdesk.domain.externalsetup.ExternalSetupSource;
import com.riskdesk.domain.externalsetup.ExternalSetupStatus;
import com.riskdesk.domain.externalsetup.port.ExternalSetupRepositoryPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.config.ExternalSetupProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.lenient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ExternalSetupServiceTest {

    @Mock ExternalSetupRepositoryPort repository;
    @Mock OrderFlowQuickExecutionService quickExec;
    @Mock ExecutionManagerService executionManager;
    @Mock SimpMessagingTemplate messagingTemplate;
    @Mock IndicatorService indicatorService;

    ExternalSetupProperties props;
    Clock fixedClock;
    ExternalSetupService service;

    @BeforeEach
    void setup() {
        props = new ExternalSetupProperties();
        props.setEnabled(true);
        props.setApiToken("dummy");
        props.setDefaultBrokerAccount("U1234567");
        props.setTtl(Map.of("MNQ", Duration.ofMinutes(3)));
        fixedClock = Clock.fixed(Instant.parse("2026-04-28T10:00:00Z"), ZoneOffset.UTC);
        service = new ExternalSetupService(repository, props, quickExec, executionManager, messagingTemplate, fixedClock, indicatorService);
        // Default: choppy regime (EMA values null → CHOPPY) — allows all setups unless overridden per-test
        lenient().when(indicatorService.computeSnapshot(any(), any())).thenReturn(snapshotWithEmas(null, null, null, false));
    }

    @Test
    void submit_persistsPendingWithPerInstrumentTtl() {
        when(repository.save(any())).thenAnswer(inv -> {
            ExternalSetup s = inv.getArgument(0);
            s.setId(42L);
            return s;
        });

        ExternalSetup saved = service.submit(new ExternalSetupSubmissionCommand(
            Instrument.MNQ,
            Side.SHORT,
            new BigDecimal("27258"),
            new BigDecimal("27278"),
            new BigDecimal("27240"),
            null,
            ExternalSetupConfidence.HIGH,
            "T#2 ABS BEAR + T#3 DIST",
            "{\"abs\":15}",
            ExternalSetupSource.CLAUDE_WAKEUP,
            "claude-wakeup:1234"
        ));

        assertThat(saved.getId()).isEqualTo(42L);
        assertThat(saved.getStatus()).isEqualTo(ExternalSetupStatus.PENDING);
        assertThat(saved.getInstrument()).isEqualTo(Instrument.MNQ);
        assertThat(saved.getDirection()).isEqualTo(Side.SHORT);
        // MNQ TTL was overridden to 3 min: expiresAt = clock + 3min
        assertThat(saved.getExpiresAt()).isEqualTo(Instant.parse("2026-04-28T10:03:00Z"));
        assertThat(saved.getSetupKey()).startsWith("es:MNQ:SHORT:");
    }

    @Test
    void submit_rejectsLongWithSlAboveEntry() {
        assertThatThrownBy(() -> service.submit(new ExternalSetupSubmissionCommand(
            Instrument.MNQ,
            Side.LONG,
            new BigDecimal("27258"),
            new BigDecimal("27300"),  // sl > entry — invalid for LONG
            new BigDecimal("27400"),
            null,
            ExternalSetupConfidence.MEDIUM,
            null, null,
            ExternalSetupSource.CLAUDE_WAKEUP,
            "ref"
        ))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("LONG requires");
    }

    @Test
    void submit_rejectsShortWithTpAboveEntry() {
        assertThatThrownBy(() -> service.submit(new ExternalSetupSubmissionCommand(
            Instrument.MNQ,
            Side.SHORT,
            new BigDecimal("27258"),
            new BigDecimal("27278"),
            new BigDecimal("27300"),  // tp > entry — invalid for SHORT
            null,
            ExternalSetupConfidence.MEDIUM,
            null, null,
            ExternalSetupSource.CLAUDE_WAKEUP,
            "ref"
        ))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SHORT requires");
    }

    @Test
    void submit_throwsWhenDisabled() {
        props.setEnabled(false);
        assertThatThrownBy(() -> service.submit(new ExternalSetupSubmissionCommand(
            Instrument.MNQ,
            Side.LONG,
            new BigDecimal("27258"),
            new BigDecimal("27240"),
            new BigDecimal("27280"),
            null,
            ExternalSetupConfidence.MEDIUM,
            null, null,
            ExternalSetupSource.CLAUDE_WAKEUP,
            "ref"
        ))).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("disabled");
    }

    @Test
    void validate_marksExecutedAndArmsTrade() {
        ExternalSetup pending = newPending(Side.SHORT, "27258", "27278", "27240");
        when(repository.findById(7L)).thenReturn(Optional.of(pending));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(quickExec.isEnabled()).thenReturn(true);
        TradeExecutionRecord exec = new TradeExecutionRecord();
        exec.setId(99L);
        when(quickExec.arm(any(OrderFlowQuickExecutionCommand.class))).thenReturn(exec);

        ExternalSetup result = service.validate(new ExternalSetupValidationCommand(
            7L, "alice", 2, "U1234567", new BigDecimal("27260")));

        assertThat(result.getStatus()).isEqualTo(ExternalSetupStatus.EXECUTED);
        assertThat(result.getTradeExecutionId()).isEqualTo(99L);
        assertThat(result.getValidatedBy()).isEqualTo("alice");
        assertThat(result.getExecutedAtPrice()).isEqualByComparingTo("27260");

        ArgumentCaptor<OrderFlowQuickExecutionCommand> armCmd = ArgumentCaptor.forClass(OrderFlowQuickExecutionCommand.class);
        verify(quickExec).arm(armCmd.capture());
        assertThat(armCmd.getValue().instrument()).isEqualTo("MNQ");
        assertThat(armCmd.getValue().action()).isEqualTo("SHORT");
        assertThat(armCmd.getValue().entryPrice()).isEqualTo(27260.0d);
        assertThat(armCmd.getValue().quantity()).isEqualTo(2);

        verify(executionManager).submitEntryOrder(any(SubmitEntryOrderCommand.class));
    }

    @Test
    void validate_rejectsExpired() {
        ExternalSetup pending = newPending(Side.SHORT, "27258", "27278", "27240");
        pending.setExpiresAt(Instant.parse("2026-04-28T09:00:00Z")); // already past
        when(repository.findById(7L)).thenReturn(Optional.of(pending));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.validate(new ExternalSetupValidationCommand(
            7L, "alice", null, null, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("expired");

        verify(quickExec, never()).arm(any());
    }

    @Test
    void validate_rejectsNonPending() {
        ExternalSetup pending = newPending(Side.SHORT, "27258", "27278", "27240");
        pending.setStatus(ExternalSetupStatus.REJECTED);
        when(repository.findById(7L)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.validate(new ExternalSetupValidationCommand(
            7L, "alice", null, null, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not pending");
    }

    @Test
    void reject_marksRejected() {
        ExternalSetup pending = newPending(Side.LONG, "27258", "27240", "27280");
        when(repository.findById(7L)).thenReturn(Optional.of(pending));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExternalSetup result = service.reject(7L, "bob", "false signal");

        assertThat(result.getStatus()).isEqualTo(ExternalSetupStatus.REJECTED);
        assertThat(result.getRejectionReason()).isEqualTo("false signal");
        assertThat(result.getValidatedBy()).isEqualTo("bob");
    }

    @Test
    void expirePending_marksAllExpired() {
        ExternalSetup s1 = newPending(Side.LONG, "27000", "26980", "27040");
        s1.setId(1L);
        ExternalSetup s2 = newPending(Side.SHORT, "27500", "27520", "27460");
        s2.setId(2L);
        when(repository.findPendingExpiredAt(any(Instant.class), any(Integer.class)))
            .thenReturn(List.of(s1, s2));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int n = service.expirePending();

        assertThat(n).isEqualTo(2);
        assertThat(s1.getStatus()).isEqualTo(ExternalSetupStatus.EXPIRED);
        assertThat(s2.getStatus()).isEqualTo(ExternalSetupStatus.EXPIRED);
    }

    @Test
    void validate_failsWhenQuickExecDisabled() {
        ExternalSetup pending = newPending(Side.LONG, "27258", "27240", "27280");
        when(repository.findById(7L)).thenReturn(Optional.of(pending));
        when(quickExec.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.validate(new ExternalSetupValidationCommand(
            7L, "alice", null, null, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("quick-execution.enabled");
    }

    // ── Regime filter tests ───────────────────────────────────────────────────

    @Test
    void submit_blocksLongWhenRegimeIsTrendingDown() {
        // EMA9 < EMA50 < EMA200 + bbExpanding → TRENDING_DOWN → LONG must be rejected
        when(indicatorService.computeSnapshot(any(), any())).thenReturn(
            snapshotWithEmas(new BigDecimal("19000"), new BigDecimal("19500"), new BigDecimal("20000"), true));

        assertThatThrownBy(() -> service.submit(new ExternalSetupSubmissionCommand(
            Instrument.MNQ, Side.LONG,
            new BigDecimal("27258"), new BigDecimal("27240"), new BigDecimal("27280"),
            null, ExternalSetupConfidence.HIGH, "test", "{}",
            ExternalSetupSource.CLAUDE_WAKEUP, "ref"
        ))).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("counter-trend")
            .hasMessageContaining("TRENDING_DOWN");
    }

    @Test
    void submit_blocksShortWhenRegimeIsTrendingUp() {
        // EMA9 > EMA50 > EMA200 + bbExpanding → TRENDING_UP → SHORT must be rejected
        when(indicatorService.computeSnapshot(any(), any())).thenReturn(
            snapshotWithEmas(new BigDecimal("21000"), new BigDecimal("20500"), new BigDecimal("20000"), true));

        assertThatThrownBy(() -> service.submit(new ExternalSetupSubmissionCommand(
            Instrument.MNQ, Side.SHORT,
            new BigDecimal("27258"), new BigDecimal("27278"), new BigDecimal("27240"),
            null, ExternalSetupConfidence.HIGH, "test", "{}",
            ExternalSetupSource.CLAUDE_WAKEUP, "ref"
        ))).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("counter-trend")
            .hasMessageContaining("TRENDING_UP");
    }

    @Test
    void submit_allowsShortWhenRegimeIsTrendingDown() {
        // SHORT continuation in bear regime → allowed
        when(indicatorService.computeSnapshot(any(), any())).thenReturn(
            snapshotWithEmas(new BigDecimal("19000"), new BigDecimal("19500"), new BigDecimal("20000"), true));
        when(repository.save(any())).thenAnswer(inv -> {
            ExternalSetup s = inv.getArgument(0);
            s.setId(10L);
            return s;
        });

        ExternalSetup saved = service.submit(new ExternalSetupSubmissionCommand(
            Instrument.MNQ, Side.SHORT,
            new BigDecimal("27258"), new BigDecimal("27278"), new BigDecimal("27240"),
            null, ExternalSetupConfidence.MEDIUM, "test", "{}",
            ExternalSetupSource.CLAUDE_WAKEUP, "ref"
        ));
        assertThat(saved.getStatus()).isEqualTo(ExternalSetupStatus.PENDING);
    }

    @Test
    void submit_allowsLongWhenRegimeIsRanging() {
        // Ranging regime — no direction block for either side
        when(indicatorService.computeSnapshot(any(), any())).thenReturn(
            // EMAs close together (within 0.2%) + bbExpanding=false → RANGING
            snapshotWithEmas(new BigDecimal("20000"), new BigDecimal("20001"), new BigDecimal("20200"), false));
        when(repository.save(any())).thenAnswer(inv -> {
            ExternalSetup s = inv.getArgument(0);
            s.setId(11L);
            return s;
        });

        ExternalSetup saved = service.submit(new ExternalSetupSubmissionCommand(
            Instrument.MNQ, Side.LONG,
            new BigDecimal("27258"), new BigDecimal("27240"), new BigDecimal("27280"),
            null, ExternalSetupConfidence.MEDIUM, "test", "{}",
            ExternalSetupSource.CLAUDE_WAKEUP, "ref"
        ));
        assertThat(saved.getStatus()).isEqualTo(ExternalSetupStatus.PENDING);
    }

    @Test
    void submit_allowsWhenIndicatorServiceFails() {
        // Regime filter must fail-open: if the query throws, the setup is still accepted
        when(indicatorService.computeSnapshot(any(), any())).thenThrow(new RuntimeException("db unavailable"));
        when(repository.save(any())).thenAnswer(inv -> {
            ExternalSetup s = inv.getArgument(0);
            s.setId(12L);
            return s;
        });

        ExternalSetup saved = service.submit(new ExternalSetupSubmissionCommand(
            Instrument.MNQ, Side.LONG,
            new BigDecimal("27258"), new BigDecimal("27240"), new BigDecimal("27280"),
            null, ExternalSetupConfidence.MEDIUM, "test", "{}",
            ExternalSetupSource.CLAUDE_WAKEUP, "ref"
        ));
        assertThat(saved.getStatus()).isEqualTo(ExternalSetupStatus.PENDING);
    }

    private ExternalSetup newPending(Side side, String entry, String sl, String tp) {
        return ExternalSetup.forSubmission(
            "es:test:" + side + ":1",
            Instrument.MNQ, side,
            new BigDecimal(entry), new BigDecimal(sl), new BigDecimal(tp), null,
            ExternalSetupConfidence.MEDIUM, "T#2", "{}",
            ExternalSetupSource.CLAUDE_WAKEUP, "ref",
            Instant.now(fixedClock),
            Instant.now(fixedClock).plus(Duration.ofMinutes(5)));
    }

    /**
     * Builds a minimal IndicatorSnapshot with the four fields that drive MarketRegimeDetector.
     * All other fields are null / false / empty — enough for the regime filter tests.
     */
    @SuppressWarnings("all")
    private static IndicatorSnapshot snapshotWithEmas(BigDecimal ema9, BigDecimal ema50, BigDecimal ema200, boolean bbExpanding) {
        return new IndicatorSnapshot(
            "MNQ", "1H",
            ema9, ema50, ema200, null,
            null, null,
            null, null, null, null,
            null, false,
            null, null, null,
            null, null,
            null, null, null, null, null,
            null, bbExpanding, null,
            null, null, null, null,
            null, null, null, null, null,
            // Stochastic
            null, null, null, null,
            // SMC: Internal
            null, null, null, null, null, null,
            // SMC: Swing
            null, null, null, null, null, null,
            // confluence filter
            false,
            // multi-resolution bias
            null,
            // SMC: Legacy
            "UNDEFINED", null, null, null, null, null,
            null, null, null, null,
            // Liquidity
            List.of(), List.of(),
            // PD zones
            null, null, null, null,
            // SMC Zones
            List.of(), List.of(), List.of(),
            List.of(),
            List.of(),
            // MTF levels
            null,
            // Session PD Array
            null, null, null, null,
            // Volume Profile
            null, null, null,
            // Session phase
            null,
            null,
            null
        );
    }
}
