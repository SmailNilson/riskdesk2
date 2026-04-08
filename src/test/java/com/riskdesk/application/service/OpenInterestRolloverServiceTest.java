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
            ibkrProperties, rolloverDetectionService, messagingTemplate, false
        );
    }

    @Test
    void recommendsRoll_whenNextOiExceedsCurrent() {
        setupMclContracts("202505", "202506");
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
        // but WebSocket alert should be sent
        verify(messagingTemplate).convertAndSend(eq("/topic/rollover"), any(Map.class));
    }

    @Test
    void holds_whenCurrentOiExceedsNext() {
        setupMclContracts("202505", "202506");
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
    void autoConfirm_callsConfirmRollover() {
        // Create service with auto-confirm enabled
        OpenInterestRolloverService autoService = new OpenInterestRolloverService(
            contractRegistry, contractResolver, openInterestProvider,
            ibkrProperties, rolloverDetectionService, messagingTemplate, true
        );

        setupMclContracts("202505", "202506");
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202505")).thenReturn(OptionalLong.of(50_000));
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202506")).thenReturn(OptionalLong.of(80_000));

        autoService.checkAllNow();

        verify(rolloverDetectionService).confirmRollover(Instrument.MCL, "202506");
    }

    @Test
    void unavailableOi_returnsUnavailable() {
        when(contractRegistry.getContractMonth(Instrument.MCL)).thenReturn(java.util.Optional.of("202505"));
        setupMclContracts("202505", "202506");
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

    private void setupMclContracts(String frontMonth, String nextMonth) {
        when(contractRegistry.getContractMonth(Instrument.MCL)).thenReturn(java.util.Optional.of(frontMonth));

        Contract c1 = new Contract();
        c1.lastTradeDateOrContractMonth(frontMonth);
        Contract c2 = new Contract();
        c2.lastTradeDateOrContractMonth(nextMonth);

        when(contractResolver.resolveNextContracts(Instrument.MCL))
            .thenReturn(List.of(
                new IbGatewayResolvedContract(Instrument.MCL, c1, null),
                new IbGatewayResolvedContract(Instrument.MCL, c2, null)
            ));

        // Stub other instruments as having no contract
        for (Instrument inst : Instrument.exchangeTradedFutures()) {
            if (inst != Instrument.MCL) {
                lenient().when(contractRegistry.getContractMonth(inst)).thenReturn(java.util.Optional.empty());
            }
        }
    }
}
