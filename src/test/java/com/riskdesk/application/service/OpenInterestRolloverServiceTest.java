package com.riskdesk.application.service;

import com.ib.client.Contract;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.port.OpenInterestProvider;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayContractResolver;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayResolvedContract;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenInterestRolloverServiceTest {

    private static final double DEFAULT_RATIO = 2.0;
    private static final long   DEFAULT_MIN_OI = 100L;

    @Mock private ActiveContractRegistry contractRegistry;
    @Mock private IbGatewayContractResolver contractResolver;
    @Mock private OpenInterestProvider openInterestProvider;
    @Mock private IbkrProperties ibkrProperties;
    @Mock private RolloverDetectionService rolloverDetectionService;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private OpenInterestRolloverService service;

    @BeforeEach
    void setUp() {
        lenient().when(ibkrProperties.isEnabled()).thenReturn(true);
        service = new OpenInterestRolloverService(
            contractRegistry, contractResolver, openInterestProvider,
            ibkrProperties, rolloverDetectionService, messagingTemplate,
            false, DEFAULT_RATIO, DEFAULT_MIN_OI
        );
    }

    @Test
    void recommendsRoll_whenNextOiExceedsCurrent() {
        setupInstrumentContracts(Instrument.MCL, "202505", "202506");
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202505")).thenReturn(OptionalLong.of(50_000));
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202506")).thenReturn(OptionalLong.of(80_000));

        Map<String, Object> result = service.checkAllNow();

        @SuppressWarnings("unchecked")
        Map<String, Object> mcl = (Map<String, Object>) result.get("MCL");
        assertEquals("RECOMMEND_ROLL", mcl.get("action"));
        assertEquals(50_000L, mcl.get("currentOI"));
        assertEquals(80_000L, mcl.get("nextOI"));

        // auto-confirm is false, so confirmRollover should NOT be called
        verify(rolloverDetectionService, never()).confirmRollover(any(), any());
        // WebSocket alert should fire with autoConfirmed=false reflecting the actual outcome
        Map<String, Object> alert = captureAlertPayload();
        assertEquals(Boolean.FALSE, alert.get("autoConfirmed"));
        assertEquals("RECOMMEND_ROLL", alert.get("action"));
    }

    @Test
    void holds_whenCurrentOiExceedsNext() {
        setupInstrumentContracts(Instrument.MCL, "202505", "202506");
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202505")).thenReturn(OptionalLong.of(80_000));
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202506")).thenReturn(OptionalLong.of(50_000));

        Map<String, Object> result = service.checkAllNow();

        @SuppressWarnings("unchecked")
        Map<String, Object> mcl = (Map<String, Object>) result.get("MCL");
        assertEquals("HOLD", mcl.get("action"));

        verify(rolloverDetectionService, never()).confirmRollover(any(), any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Map.class));
    }

    @Test
    void autoConfirm_triggersForNonEnergyAboveRatio() {
        // MNQ (EQUITY_INDEX), nextOI / currentOI = 3x — well above the 2x threshold.
        OpenInterestRolloverService autoService = autoConfirmServiceWith(DEFAULT_RATIO, DEFAULT_MIN_OI);
        setupInstrumentContracts(Instrument.MNQ, "202606", "202609");
        when(openInterestProvider.fetchOpenInterest(Instrument.MNQ, "202606")).thenReturn(OptionalLong.of(40_000));
        when(openInterestProvider.fetchOpenInterest(Instrument.MNQ, "202609")).thenReturn(OptionalLong.of(120_000));

        autoService.checkAllNow();

        verify(rolloverDetectionService).confirmRollover(Instrument.MNQ, "202609");
        // Only on this path should the payload report autoConfirmed=true.
        Map<String, Object> alert = captureAlertPayload();
        assertEquals(Boolean.TRUE, alert.get("autoConfirmed"));
    }

    @Test
    void autoConfirm_suppressedForEnergyEvenWithClearDominance() {
        // Regression test for 2026-04-10 MCL incident: even with overwhelming OI
        // dominance on the next month, ENERGY should never auto-roll via OI shift
        // because IBKR's expiry-month semantics are offset from delivery month.
        OpenInterestRolloverService autoService = autoConfirmServiceWith(DEFAULT_RATIO, DEFAULT_MIN_OI);
        setupInstrumentContracts(Instrument.MCL, "202604", "202605");
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202604")).thenReturn(OptionalLong.of(20_000));
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202605")).thenReturn(OptionalLong.of(200_000));

        autoService.checkAllNow();

        verify(rolloverDetectionService, never()).confirmRollover(any(), any());
        // Alert must report autoConfirmed=false so the UI keeps the manual-confirm
        // prompt open — reporting the config flag (true) would hide it.
        Map<String, Object> alert = captureAlertPayload();
        assertEquals(Boolean.FALSE, alert.get("autoConfirmed"));
    }

    @Test
    void autoConfirm_suppressedBelowRatioThreshold() {
        // 1.6x < 2.0x threshold — a thin OI lead that historically produced
        // spurious rollovers on quarterly products like MNQ.
        OpenInterestRolloverService autoService = autoConfirmServiceWith(DEFAULT_RATIO, DEFAULT_MIN_OI);
        setupInstrumentContracts(Instrument.MNQ, "202606", "202609");
        when(openInterestProvider.fetchOpenInterest(Instrument.MNQ, "202606")).thenReturn(OptionalLong.of(50_000));
        when(openInterestProvider.fetchOpenInterest(Instrument.MNQ, "202609")).thenReturn(OptionalLong.of(80_000));

        autoService.checkAllNow();

        verify(rolloverDetectionService, never()).confirmRollover(any(), any());
        Map<String, Object> alert = captureAlertPayload();
        assertEquals(Boolean.FALSE, alert.get("autoConfirmed"));
    }

    @Test
    void autoConfirm_suppressedWhenCurrentOiBelowMinimum() {
        // Stale-data guard: currentOI (50) below the 100 floor — treated as
        // unreliable snapshot, not a real liquidity migration.
        OpenInterestRolloverService autoService = autoConfirmServiceWith(DEFAULT_RATIO, DEFAULT_MIN_OI);
        setupInstrumentContracts(Instrument.MNQ, "202606", "202609");
        when(openInterestProvider.fetchOpenInterest(Instrument.MNQ, "202606")).thenReturn(OptionalLong.of(50));
        when(openInterestProvider.fetchOpenInterest(Instrument.MNQ, "202609")).thenReturn(OptionalLong.of(10_000));

        autoService.checkAllNow();

        verify(rolloverDetectionService, never()).confirmRollover(any(), any());
        Map<String, Object> alert = captureAlertPayload();
        assertEquals(Boolean.FALSE, alert.get("autoConfirmed"));
    }

    @Test
    void unavailableOi_returnsUnavailable() {
        when(contractRegistry.getContractMonth(Instrument.MCL)).thenReturn(java.util.Optional.of("202505"));
        setupInstrumentContracts(Instrument.MCL, "202505", "202506");
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202505")).thenReturn(OptionalLong.empty());
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202506")).thenReturn(OptionalLong.empty());

        Map<String, Object> result = service.checkAllNow();

        @SuppressWarnings("unchecked")
        Map<String, Object> mcl = (Map<String, Object>) result.get("MCL");
        assertEquals("UNAVAILABLE", mcl.get("status"));
    }

    @Test
    void singleContractAvailable_returnsUnavailable() {
        // No contractRegistry stub needed — checkInstrument() returns early
        // when fewer than 2 contracts are available from IBKR.

        Contract c = new Contract();
        c.lastTradeDateOrContractMonth("202505");
        when(contractResolver.resolveNextContracts(Instrument.MCL))
            .thenReturn(List.of(new IbGatewayResolvedContract(Instrument.MCL, c, null)));

        Map<String, Object> result = service.checkAllNow();

        @SuppressWarnings("unchecked")
        Map<String, Object> mcl = (Map<String, Object>) result.get("MCL");
        assertEquals("UNAVAILABLE", mcl.get("status"));
    }

    // ─────────────────────────────────────────────────────────────────────────

    private OpenInterestRolloverService autoConfirmServiceWith(double ratio, long minOi) {
        return new OpenInterestRolloverService(
            contractRegistry, contractResolver, openInterestProvider,
            ibkrProperties, rolloverDetectionService, messagingTemplate,
            true, ratio, minOi
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureAlertPayload() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/rollover"), captor.capture());
        return captor.getValue();
    }

    private void setupInstrumentContracts(Instrument target, String frontMonth, String nextMonth) {
        when(contractRegistry.getContractMonth(target)).thenReturn(java.util.Optional.of(frontMonth));

        Contract c1 = new Contract();
        c1.lastTradeDateOrContractMonth(frontMonth);
        Contract c2 = new Contract();
        c2.lastTradeDateOrContractMonth(nextMonth);

        when(contractResolver.resolveNextContracts(target))
            .thenReturn(List.of(
                new IbGatewayResolvedContract(target, c1, null),
                new IbGatewayResolvedContract(target, c2, null)
            ));

        // Stub other instruments as having no contract
        for (Instrument inst : Instrument.exchangeTradedFutures()) {
            if (inst != target) {
                lenient().when(contractRegistry.getContractMonth(inst)).thenReturn(java.util.Optional.empty());
            }
        }
    }
}
