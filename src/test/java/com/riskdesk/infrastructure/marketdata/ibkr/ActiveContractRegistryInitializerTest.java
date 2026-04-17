package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.port.OpenInterestProvider;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActiveContractRegistryInitializerTest {

    private ActiveContractRegistry registry;
    @Mock private IbGatewayContractResolver resolver;
    @Mock private IbkrProperties ibkrProperties;
    @Mock private OpenInterestProvider openInterestProvider;

    private ActiveContractRegistryInitializer initializer;

    @BeforeEach
    void setUp() {
        registry = new ActiveContractRegistry();
        initializer = new ActiveContractRegistryInitializer(registry, resolver, ibkrProperties, openInterestProvider);
        setFallbacks("202505", "202506", "202506", "202506");
    }

    @Test
    void ibkrDisabled_usesFallbacks() {
        when(ibkrProperties.isEnabled()).thenReturn(false);

        initializer.run(new DefaultApplicationArguments());

        assertEquals("202505", registry.getContractMonth(Instrument.MCL).orElse(null));
        assertEquals("202506", registry.getContractMonth(Instrument.MGC).orElse(null));
        assertEquals("202506", registry.getContractMonth(Instrument.MNQ).orElse(null));
        assertEquals("202506", registry.getContractMonth(Instrument.E6).orElse(null));
        verifyNoInteractions(resolver);
    }

    @Test
    void ibkrEnabled_frontMonthSelected_whenFrontOiIsHigher() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        mockTopTwo(Instrument.MCL, "202505", "202506");
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202505")).thenReturn(OptionalLong.of(80_000));
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202506")).thenReturn(OptionalLong.of(50_000));
        stubOtherInstrumentsEmpty();

        initializer.run(new DefaultApplicationArguments());

        assertEquals("202505", registry.getContractMonth(Instrument.MCL).orElse(null));
        verify(resolver).setResolved(eq(Instrument.MCL), argThat(r -> "202505".equals(normalizeMonth(r))));
    }

    @Test
    void ibkrEnabled_nextMonthSelected_whenNextOiIsHigher() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        mockTopTwo(Instrument.MCL, "202505", "202506");
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202505")).thenReturn(OptionalLong.of(50_000));
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202506")).thenReturn(OptionalLong.of(80_000));
        stubOtherInstrumentsEmpty();

        initializer.run(new DefaultApplicationArguments());

        assertEquals("202506", registry.getContractMonth(Instrument.MCL).orElse(null));
        verify(resolver).setResolved(eq(Instrument.MCL), argThat(r -> "202506".equals(normalizeMonth(r))));
    }

    @Test
    void ibkrEnabled_frontMonthSelected_whenOiIsEqual() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        mockTopTwo(Instrument.MCL, "202505", "202506");
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202505")).thenReturn(OptionalLong.of(60_000));
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202506")).thenReturn(OptionalLong.of(60_000));
        stubOtherInstrumentsEmpty();

        initializer.run(new DefaultApplicationArguments());

        assertEquals("202505", registry.getContractMonth(Instrument.MCL).orElse(null));
    }

    @Test
    void ibkrEnabled_frontMonthSelected_whenOiUnavailable() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        mockTopTwo(Instrument.MCL, "202505", "202506");
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202505")).thenReturn(OptionalLong.empty());
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202506")).thenReturn(OptionalLong.empty());
        stubOtherInstrumentsEmpty();

        initializer.run(new DefaultApplicationArguments());

        assertEquals("202505", registry.getContractMonth(Instrument.MCL).orElse(null));
    }

    @Test
    void ibkrEnabled_singleContract_usesIt() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        Contract c = buildContract("202505");
        when(resolver.resolveTopTwo(Instrument.MCL))
            .thenReturn(List.of(new IbGatewayResolvedContract(Instrument.MCL, c, null)));
        stubOtherInstrumentsEmpty();

        initializer.run(new DefaultApplicationArguments());

        assertEquals("202505", registry.getContractMonth(Instrument.MCL).orElse(null));
    }

    @Test
    void ibkrEnabled_emptyResolution_fallsBackToProperties() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        when(resolver.resolveTopTwo(Instrument.MCL)).thenReturn(List.of());
        stubOtherInstrumentsEmpty();

        initializer.run(new DefaultApplicationArguments());

        assertEquals("202505", registry.getContractMonth(Instrument.MCL).orElse(null));
    }

    @Test
    void ibkrEnabled_resolverThrows_fallsBackToProperties() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        when(resolver.resolveTopTwo(Instrument.MCL)).thenThrow(new RuntimeException("IBKR timeout"));
        stubOtherInstrumentsEmpty();

        initializer.run(new DefaultApplicationArguments());

        assertEquals("202505", registry.getContractMonth(Instrument.MCL).orElse(null));
    }

    @Test
    void normalizeMonth_handlesFullDateFormat() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        Contract c1 = buildContract("20250520");
        Contract c2 = buildContract("20250620");
        when(resolver.resolveTopTwo(Instrument.MCL))
            .thenReturn(List.of(
                new IbGatewayResolvedContract(Instrument.MCL, c1, null),
                new IbGatewayResolvedContract(Instrument.MCL, c2, null)
            ));
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202505")).thenReturn(OptionalLong.of(50_000));
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202506")).thenReturn(OptionalLong.of(80_000));
        stubOtherInstrumentsEmpty();

        initializer.run(new DefaultApplicationArguments());

        assertEquals("202506", registry.getContractMonth(Instrument.MCL).orElse(null));
    }

    @Test
    void allInstrumentsInitialized() {
        when(ibkrProperties.isEnabled()).thenReturn(false);

        initializer.run(new DefaultApplicationArguments());

        for (Instrument inst : Instrument.exchangeTradedFutures()) {
            assertTrue(registry.getContractMonth(inst).isPresent(),
                "Missing contract month for " + inst);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void mockTopTwo(Instrument instrument, String frontMonth, String nextMonth) {
        Contract c1 = buildContract(frontMonth);
        Contract c2 = buildContract(nextMonth);
        when(resolver.resolveTopTwo(instrument))
            .thenReturn(List.of(
                new IbGatewayResolvedContract(instrument, c1, null),
                new IbGatewayResolvedContract(instrument, c2, null)
            ));
    }

    private Contract buildContract(String month) {
        Contract c = new Contract();
        c.lastTradeDateOrContractMonth(month);
        return c;
    }

    private void stubOtherInstrumentsEmpty() {
        for (Instrument inst : Instrument.exchangeTradedFutures()) {
            if (inst != Instrument.MCL) {
                lenient().when(resolver.resolveTopTwo(inst)).thenReturn(List.of());
            }
        }
    }

    private void setFallbacks(String mcl, String mgc, String mnq, String e6) {
        try {
            var field = ActiveContractRegistryInitializer.class.getDeclaredField("fallbackMcl");
            field.setAccessible(true);
            field.set(initializer, mcl);
            field = ActiveContractRegistryInitializer.class.getDeclaredField("fallbackMgc");
            field.setAccessible(true);
            field.set(initializer, mgc);
            field = ActiveContractRegistryInitializer.class.getDeclaredField("fallbackMnq");
            field.setAccessible(true);
            field.set(initializer, mnq);
            field = ActiveContractRegistryInitializer.class.getDeclaredField("fallbackE6");
            field.setAccessible(true);
            field.set(initializer, e6);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String normalizeMonth(IbGatewayResolvedContract resolved) {
        String raw = resolved.contract().lastTradeDateOrContractMonth();
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() >= 6 ? digits.substring(0, 6) : null;
    }
}
