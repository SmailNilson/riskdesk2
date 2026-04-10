package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.port.OpenInterestProvider;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.OptionalLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ActiveContractRegistryInitializer}.
 *
 * Covers:
 * - OI-based contract selection (next > front)
 * - Front-month selection when OI favors front
 * - Equal OI selects front month
 * - Single contract from resolveTopTwo
 * - Empty resolveTopTwo → fallback to properties
 * - IBKR disabled → fallback to properties
 * - IBKR exception → fallback to properties
 * - OI unavailable (empty OptionalLong) → selects front month
 * - setResolved called to seed resolver cache
 * - normalizeMonth edge cases
 */
@DisplayName("ActiveContractRegistryInitializer — startup contract resolution")
class ActiveContractRegistryInitializerTest {

    private ActiveContractRegistry registry;
    private IbGatewayContractResolver resolver;
    private IbkrProperties ibkrProperties;
    private OpenInterestProvider openInterestProvider;
    private ActiveContractRegistryInitializer initializer;

    @BeforeEach
    void setUp() {
        registry = mock(ActiveContractRegistry.class);
        resolver = mock(IbGatewayContractResolver.class);
        ibkrProperties = mock(IbkrProperties.class);
        openInterestProvider = mock(OpenInterestProvider.class);

        initializer = new ActiveContractRegistryInitializer(
            registry, resolver, ibkrProperties, openInterestProvider
        );

        // Set fallback values via reflection (simulates @Value injection)
        ReflectionTestUtils.setField(initializer, "fallbackMcl", "202505");
        ReflectionTestUtils.setField(initializer, "fallbackMgc", "202506");
        ReflectionTestUtils.setField(initializer, "fallbackMnq", "202506");
        ReflectionTestUtils.setField(initializer, "fallbackE6", "202506");
    }

    // -----------------------------------------------------------------------
    // OI-based selection
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Selects next-month contract when its OI exceeds front-month OI")
    void selectsNextMonthWhenOiIsHigher() throws Exception {
        when(ibkrProperties.isEnabled()).thenReturn(true);

        IbGatewayResolvedContract front = resolvedContract(Instrument.MCL, "202505");
        IbGatewayResolvedContract next  = resolvedContract(Instrument.MCL, "202506");
        when(resolver.resolveTopTwo(Instrument.MCL)).thenReturn(List.of(front, next));

        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202505")).thenReturn(OptionalLong.of(12000));
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202506")).thenReturn(OptionalLong.of(45000));

        // Other instruments return empty
        when(resolver.resolveTopTwo(argThat(i -> i != Instrument.MCL))).thenReturn(List.of());

        initializer.run(new DefaultApplicationArguments());

        verify(registry).initialize(Instrument.MCL, "202506");
        verify(resolver).setResolved(Instrument.MCL, next);
    }

    @Test
    @DisplayName("Selects front-month contract when its OI exceeds next-month OI")
    void selectsFrontMonthWhenOiIsHigher() throws Exception {
        when(ibkrProperties.isEnabled()).thenReturn(true);

        IbGatewayResolvedContract front = resolvedContract(Instrument.MGC, "202504");
        IbGatewayResolvedContract next  = resolvedContract(Instrument.MGC, "202506");
        when(resolver.resolveTopTwo(Instrument.MGC)).thenReturn(List.of(front, next));

        when(openInterestProvider.fetchOpenInterest(Instrument.MGC, "202504")).thenReturn(OptionalLong.of(80000));
        when(openInterestProvider.fetchOpenInterest(Instrument.MGC, "202506")).thenReturn(OptionalLong.of(30000));

        when(resolver.resolveTopTwo(argThat(i -> i != Instrument.MGC))).thenReturn(List.of());

        initializer.run(new DefaultApplicationArguments());

        verify(registry).initialize(Instrument.MGC, "202504");
        verify(resolver).setResolved(Instrument.MGC, front);
    }

    @Test
    @DisplayName("Selects front-month when OI is equal (strict > comparison)")
    void selectsFrontMonthWhenOiIsEqual() throws Exception {
        when(ibkrProperties.isEnabled()).thenReturn(true);

        IbGatewayResolvedContract front = resolvedContract(Instrument.MNQ, "202506");
        IbGatewayResolvedContract next  = resolvedContract(Instrument.MNQ, "202509");
        when(resolver.resolveTopTwo(Instrument.MNQ)).thenReturn(List.of(front, next));

        when(openInterestProvider.fetchOpenInterest(Instrument.MNQ, "202506")).thenReturn(OptionalLong.of(50000));
        when(openInterestProvider.fetchOpenInterest(Instrument.MNQ, "202509")).thenReturn(OptionalLong.of(50000));

        when(resolver.resolveTopTwo(argThat(i -> i != Instrument.MNQ))).thenReturn(List.of());

        initializer.run(new DefaultApplicationArguments());

        verify(registry).initialize(Instrument.MNQ, "202506");
        verify(resolver).setResolved(Instrument.MNQ, front);
    }

    // -----------------------------------------------------------------------
    // OI unavailable
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Selects front-month when OI data is unavailable for both contracts")
    void selectsFrontMonthWhenOiUnavailable() throws Exception {
        when(ibkrProperties.isEnabled()).thenReturn(true);

        IbGatewayResolvedContract front = resolvedContract(Instrument.E6, "202506");
        IbGatewayResolvedContract next  = resolvedContract(Instrument.E6, "202509");
        when(resolver.resolveTopTwo(Instrument.E6)).thenReturn(List.of(front, next));

        when(openInterestProvider.fetchOpenInterest(any(), any())).thenReturn(OptionalLong.empty());

        when(resolver.resolveTopTwo(argThat(i -> i != Instrument.E6))).thenReturn(List.of());

        initializer.run(new DefaultApplicationArguments());

        verify(registry).initialize(Instrument.E6, "202506");
        verify(resolver).setResolved(Instrument.E6, front);
    }

    @Test
    @DisplayName("Selects front-month when only front OI is available (next empty)")
    void selectsFrontWhenNextOiUnavailable() throws Exception {
        when(ibkrProperties.isEnabled()).thenReturn(true);

        IbGatewayResolvedContract front = resolvedContract(Instrument.MCL, "202505");
        IbGatewayResolvedContract next  = resolvedContract(Instrument.MCL, "202506");
        when(resolver.resolveTopTwo(Instrument.MCL)).thenReturn(List.of(front, next));

        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202505")).thenReturn(OptionalLong.of(30000));
        when(openInterestProvider.fetchOpenInterest(Instrument.MCL, "202506")).thenReturn(OptionalLong.empty());

        when(resolver.resolveTopTwo(argThat(i -> i != Instrument.MCL))).thenReturn(List.of());

        initializer.run(new DefaultApplicationArguments());

        verify(registry).initialize(Instrument.MCL, "202505");
    }

    // -----------------------------------------------------------------------
    // Single contract
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Single contract from resolveTopTwo skips OI comparison")
    void singleContractSkipsOiComparison() throws Exception {
        when(ibkrProperties.isEnabled()).thenReturn(true);

        IbGatewayResolvedContract single = resolvedContract(Instrument.MCL, "202505");
        when(resolver.resolveTopTwo(Instrument.MCL)).thenReturn(List.of(single));

        when(resolver.resolveTopTwo(argThat(i -> i != Instrument.MCL))).thenReturn(List.of());

        initializer.run(new DefaultApplicationArguments());

        verify(registry).initialize(Instrument.MCL, "202505");
        verify(openInterestProvider, never()).fetchOpenInterest(eq(Instrument.MCL), any());
    }

    // -----------------------------------------------------------------------
    // Fallback paths
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Empty resolveTopTwo falls back to application properties")
    void emptyResolveTopTwoFallsBackToProperties() throws Exception {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        when(resolver.resolveTopTwo(any())).thenReturn(List.of());

        initializer.run(new DefaultApplicationArguments());

        verify(registry).initialize(Instrument.MCL, "202505");
        verify(registry).initialize(Instrument.MGC, "202506");
        verify(registry).initialize(Instrument.MNQ, "202506");
        verify(registry).initialize(Instrument.E6, "202506");
    }

    @Test
    @DisplayName("IBKR disabled falls back to application properties for all instruments")
    void ibkrDisabledFallsBackToProperties() throws Exception {
        when(ibkrProperties.isEnabled()).thenReturn(false);

        initializer.run(new DefaultApplicationArguments());

        verify(resolver, never()).resolveTopTwo(any());
        verify(registry).initialize(Instrument.MCL, "202505");
        verify(registry).initialize(Instrument.MGC, "202506");
        verify(registry).initialize(Instrument.MNQ, "202506");
        verify(registry).initialize(Instrument.E6, "202506");
    }

    @Test
    @DisplayName("IBKR exception falls back to application properties gracefully")
    void ibkrExceptionFallsBackToProperties() throws Exception {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        when(resolver.resolveTopTwo(any())).thenThrow(new RuntimeException("Connection refused"));

        initializer.run(new DefaultApplicationArguments());

        verify(registry).initialize(Instrument.MCL, "202505");
        verify(registry).initialize(Instrument.MGC, "202506");
    }

    // -----------------------------------------------------------------------
    // normalizeMonth edge cases
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Contract month with date suffix (20260520) normalizes to 202605")
    void normalizesContractMonthWithDateSuffix() throws Exception {
        when(ibkrProperties.isEnabled()).thenReturn(true);

        IbGatewayResolvedContract contract = resolvedContract(Instrument.MCL, "20260520");
        when(resolver.resolveTopTwo(Instrument.MCL)).thenReturn(List.of(contract));
        when(resolver.resolveTopTwo(argThat(i -> i != Instrument.MCL))).thenReturn(List.of());

        initializer.run(new DefaultApplicationArguments());

        verify(registry).initialize(Instrument.MCL, "202605");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static IbGatewayResolvedContract resolvedContract(Instrument instrument, String contractMonth) {
        Contract contract = new Contract();
        contract.lastTradeDateOrContractMonth(contractMonth);
        return new IbGatewayResolvedContract(instrument, contract, null);
    }
}
