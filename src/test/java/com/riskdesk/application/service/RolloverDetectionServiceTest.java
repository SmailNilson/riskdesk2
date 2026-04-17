package com.riskdesk.application.service;

import com.ib.client.Contract;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.RolloverStatus;
import com.riskdesk.domain.contract.event.ContractRolloverEvent;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RolloverDetectionServiceTest {

    @Mock private ActiveContractRegistry contractRegistry;
    @Mock private IbGatewayContractResolver resolver;
    @Mock private OpenInterestProvider openInterestProvider;
    @Mock private IbkrProperties ibkrProperties;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private HistoricalDataService historicalDataService;

    private RolloverDetectionService service;

    @BeforeEach
    void setUp() {
        service = new RolloverDetectionService(
                contractRegistry, resolver, openInterestProvider,
                ibkrProperties, messagingTemplate, eventPublisher,
                historicalDataService, 32, false);
    }

    @Test
    void confirmRollover_publishesEventWhenContractMonthChanges() {
        when(contractRegistry.getContractMonth(Instrument.MCL))
                .thenReturn(java.util.Optional.of("202606"));

        service.confirmRollover(Instrument.MCL, "202609");

        verify(contractRegistry).confirmRollover(Instrument.MCL, "202609");
        verify(resolver).refreshToMonth(Instrument.MCL, "202609");

        ArgumentCaptor<ContractRolloverEvent> captor = ArgumentCaptor.forClass(ContractRolloverEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        ContractRolloverEvent event = captor.getValue();
        assertEquals(Instrument.MCL, event.instrument());
        assertEquals("202606", event.oldContractMonth());
        assertEquals("202609", event.newContractMonth());
        assertNotNull(event.timestamp());
    }

    @Test
    void confirmRollover_doesNotPublishEventWhenSameMonth() {
        when(contractRegistry.getContractMonth(Instrument.MGC))
                .thenReturn(java.util.Optional.of("202604"));

        service.confirmRollover(Instrument.MGC, "202604");

        verify(contractRegistry).confirmRollover(Instrument.MGC, "202604");
        verify(resolver).refreshToMonth(Instrument.MGC, "202604");
        verify(eventPublisher, never()).publishEvent(any(ContractRolloverEvent.class));
    }

    @Test
    void confirmRollover_doesNotPublishEventWhenNoOldMonth() {
        when(contractRegistry.getContractMonth(Instrument.E6))
                .thenReturn(java.util.Optional.empty());

        service.confirmRollover(Instrument.E6, "202609");

        verify(contractRegistry).confirmRollover(Instrument.E6, "202609");
        verify(eventPublisher, never()).publishEvent(any(ContractRolloverEvent.class));
    }

    // ── Regression: MCL 202604 stuck in prod April 2026 ──────────────────────
    // Root cause: daysToFirstOfMonth("202604") on April 17 returns negative,
    // and the old `>= 0` guard filtered it out → status was STABLE instead of CRITICAL.
    // Fix: clamp to 0 so being inside the contract month always fires CRITICAL.

    @Test
    void getCurrentStatus_mclInPastExpiryMonth_returnsCriticalWhenOiUnavailable() {
        // Use a month that is always in the past: January 2020
        String expiredMonth = "202001";
        String nextMonth    = "202002";
        stubMclOnly(expiredMonth, nextMonth, List.of(
            resolvedContract(Instrument.MCL, expiredMonth),
            resolvedContract(Instrument.MCL, nextMonth)
        ));
        when(openInterestProvider.fetchOpenInterest(any(), any())).thenReturn(OptionalLong.empty());

        Map<String, RolloverDetectionService.RolloverInfo> status = service.getCurrentStatus();

        assertEquals(RolloverStatus.CRITICAL, status.get("MCL").status(),
            "MCL in past expiry month with OI unavailable must be CRITICAL, not STABLE");
    }

    @Test
    void getCurrentStatus_mclInPastExpiryMonth_returnsCriticalWhenFewerThanTwoContracts() {
        // Simulate IBKR dropping the near-expiry contract from its list (returns only 1)
        String expiredMonth = "202001";
        stubMclOnly(expiredMonth, null, List.of(
            resolvedContract(Instrument.MCL, expiredMonth)
        ));

        Map<String, RolloverDetectionService.RolloverInfo> status = service.getCurrentStatus();

        assertEquals(RolloverStatus.CRITICAL, status.get("MCL").status(),
            "MCL in past expiry month with <2 IBKR contracts must fall back to calendar CRITICAL");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void stubMclOnly(String currentMonth, String nextMonth,
                              List<IbGatewayResolvedContract> ibkrContracts) {
        when(contractRegistry.getContractMonth(Instrument.MCL))
            .thenReturn(java.util.Optional.of(currentMonth));
        when(resolver.resolveNextContracts(Instrument.MCL)).thenReturn(ibkrContracts);

        for (Instrument inst : Instrument.exchangeTradedFutures()) {
            if (inst != Instrument.MCL) {
                lenient().when(contractRegistry.getContractMonth(inst))
                    .thenReturn(java.util.Optional.empty());
            }
        }
    }

    private static IbGatewayResolvedContract resolvedContract(Instrument instrument, String month) {
        Contract c = new Contract();
        c.lastTradeDateOrContractMonth(month);
        return new IbGatewayResolvedContract(instrument, c, null);
    }
}
