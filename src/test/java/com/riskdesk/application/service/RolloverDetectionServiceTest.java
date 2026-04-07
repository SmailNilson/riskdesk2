package com.riskdesk.application.service;

import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.event.ContractRolloverEvent;
import com.riskdesk.domain.contract.port.OpenInterestProvider;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayContractResolver;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RolloverDetectionServiceTest {

    @Mock private ActiveContractRegistry contractRegistry;
    @Mock private IbGatewayContractResolver resolver;
    @Mock private OpenInterestProvider openInterestProvider;
    @Mock private IbkrProperties ibkrProperties;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private ApplicationEventPublisher eventPublisher;

    private RolloverDetectionService service;

    @BeforeEach
    void setUp() {
        service = new RolloverDetectionService(
                contractRegistry, resolver, openInterestProvider,
                ibkrProperties, messagingTemplate, eventPublisher, 32);
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
}
