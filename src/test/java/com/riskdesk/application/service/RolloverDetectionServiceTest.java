package com.riskdesk.application.service;

import com.ib.client.Contract;
import com.riskdesk.application.service.RolloverDetectionService.RolloverInfo;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.RolloverStatus;
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RolloverDetectionServiceTest {

    private static final ZoneId CME_ZONE = ZoneId.of("America/New_York");

    @Mock private ActiveContractRegistry contractRegistry;
    @Mock private IbGatewayContractResolver resolver;
    @Mock private IbkrProperties ibkrProperties;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private RolloverDetectionService service;

    @BeforeEach
    void setUp() {
        service = new RolloverDetectionService(contractRegistry, resolver, ibkrProperties, messagingTemplate);
        for (Instrument inst : Instrument.exchangeTradedFutures()) {
            lenient().when(contractRegistry.getContractMonth(inst)).thenReturn(Optional.empty());
        }
    }

    @Test
    void getCurrentStatus_returnsStableForFarExpiry() {
        setupInstrument(Instrument.MCL, "202506", daysFromNow(30));

        Map<String, RolloverInfo> status = service.getCurrentStatus();

        RolloverInfo mcl = status.get("MCL");
        assertNotNull(mcl);
        assertEquals(RolloverStatus.STABLE, mcl.status());
        assertEquals("202506", mcl.contractMonth());
        assertTrue(mcl.daysToExpiry() > 7);
    }

    @Test
    void getCurrentStatus_returnsWarningWhenFourToSevenDays() {
        setupInstrument(Instrument.MCL, "202506", daysFromNow(5));

        Map<String, RolloverInfo> status = service.getCurrentStatus();

        assertEquals(RolloverStatus.WARNING, status.get("MCL").status());
    }

    @Test
    void getCurrentStatus_returnsCriticalWhenOneToThreeDays() {
        setupInstrument(Instrument.MCL, "202506", daysFromNow(2));

        Map<String, RolloverInfo> status = service.getCurrentStatus();

        assertEquals(RolloverStatus.CRITICAL, status.get("MCL").status());
    }

    @Test
    void getCurrentStatus_stableWhenContractMonthUnknown() {
        Map<String, RolloverInfo> status = service.getCurrentStatus();

        assertEquals(RolloverStatus.STABLE, status.get("MCL").status());
        assertNull(status.get("MCL").contractMonth());
    }

    @Test
    void getCurrentStatus_stableWhenResolverFails() {
        when(contractRegistry.getContractMonth(Instrument.MCL)).thenReturn(Optional.of("202506"));
        when(resolver.resolve(Instrument.MCL)).thenThrow(new RuntimeException("IBKR down"));

        Map<String, RolloverInfo> status = service.getCurrentStatus();

        assertEquals(RolloverStatus.STABLE, status.get("MCL").status());
        assertEquals(-1, status.get("MCL").daysToExpiry());
    }

    @Test
    void confirmRollover_delegatesCorrectly() {
        service.confirmRollover(Instrument.MCL, "202507");

        verify(contractRegistry).confirmRollover(Instrument.MCL, "202507");
        verify(resolver).refreshToMonth(Instrument.MCL, "202507");
    }

    @Test
    void checkRollovers_ibkrDisabled_noop() {
        when(ibkrProperties.isEnabled()).thenReturn(false);

        service.checkRollovers();

        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    void checkRollovers_warningPushesWebSocketAlert() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        setupInstrument(Instrument.MCL, "202506", daysFromNow(5));

        service.checkRollovers();

        verify(messagingTemplate).convertAndSend(eq("/topic/rollover"), any(Map.class));
    }

    @Test
    void checkRollovers_stableDoesNotPush() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        setupInstrument(Instrument.MCL, "202506", daysFromNow(30));

        service.checkRollovers();

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Map.class));
    }

    @Test
    void parseExpiry_handlesYYYYMMformat() {
        when(contractRegistry.getContractMonth(Instrument.MCL)).thenReturn(Optional.of("202506"));
        Contract c = new Contract();
        c.lastTradeDateOrContractMonth("202506");
        when(resolver.resolve(Instrument.MCL))
            .thenReturn(Optional.of(new IbGatewayResolvedContract(Instrument.MCL, c, null)));

        Map<String, RolloverInfo> status = service.getCurrentStatus();

        assertNotNull(status.get("MCL").expiryDate());
    }

    @Test
    void parseExpiry_handlesFullDateFormat() {
        when(contractRegistry.getContractMonth(Instrument.MCL)).thenReturn(Optional.of("202506"));
        Contract c = new Contract();
        c.lastTradeDateOrContractMonth("20250620");
        when(resolver.resolve(Instrument.MCL))
            .thenReturn(Optional.of(new IbGatewayResolvedContract(Instrument.MCL, c, null)));

        Map<String, RolloverInfo> status = service.getCurrentStatus();

        assertEquals("2025-06-20", status.get("MCL").expiryDate());
    }

    @Test
    void getCurrentStatus_coversAllExchangeTradedInstruments() {
        Map<String, RolloverInfo> status = service.getCurrentStatus();

        for (Instrument inst : Instrument.exchangeTradedFutures()) {
            assertTrue(status.containsKey(inst.name()), "Missing status for " + inst);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void setupInstrument(Instrument instrument, String contractMonth, String expiryDate) {
        when(contractRegistry.getContractMonth(instrument)).thenReturn(Optional.of(contractMonth));
        Contract c = new Contract();
        c.lastTradeDateOrContractMonth(expiryDate);
        lenient().when(resolver.resolve(instrument))
            .thenReturn(Optional.of(new IbGatewayResolvedContract(instrument, c, null)));
    }

    private static String daysFromNow(int days) {
        return LocalDate.now(CME_ZONE).plusDays(days).format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
