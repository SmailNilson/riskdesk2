package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IbGatewayContractResolverTest {

    @Mock private IbGatewayNativeClient nativeClient;
    @Mock private ActiveContractRegistry registry;

    private IbGatewayContractResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new IbGatewayContractResolver(nativeClient, registry);
    }

    /**
     * Regression test for MCL CME-energy-month desync: when the cache is empty and
     * the registry holds a value (Single Source of Truth), resolve() must route through
     * refreshToMonth() targeting the registry month — not fall back to refresh() which
     * picks min(expiry) and would select the contract about to expire.
     */
    @Test
    void resolve_prefersRegistryMonth_overMinExpiry() {
        // Given: registry says MCL=202605 (CLM26, June delivery).
        // IBKR returns two contracts; min(expiry) would be 202604 (CLK26, expiring soon).
        when(registry.getContractMonth(Instrument.MCL)).thenReturn(Optional.of("202605"));

        Contract clk26 = new Contract();
        clk26.lastTradeDateOrContractMonth("20260421");
        clk26.conid(111);
        ContractDetails clk26Details = new ContractDetails();
        clk26Details.contract(clk26);

        Contract clm26 = new Contract();
        clm26.lastTradeDateOrContractMonth("20260519");
        clm26.conid(222);
        ContractDetails clm26Details = new ContractDetails();
        clm26Details.contract(clm26);

        // Any buildQueries(MCL) call returns both contracts.
        when(nativeClient.requestContractDetails(any(Contract.class)))
            .thenReturn(List.of(clk26Details, clm26Details));

        // When
        Optional<IbGatewayResolvedContract> result = resolver.resolve(Instrument.MCL);

        // Then: the CLM26 contract (matches registry month 202605) is returned,
        // NOT the min-expiry CLK26 (202604).
        assertThat(result).isPresent();
        assertThat(result.get().contract().conid()).isEqualTo(222);
        assertThat(result.get().contract().lastTradeDateOrContractMonth()).isEqualTo("20260519");

        // refreshToMonth() cancels subscriptions on the instrument before switching.
        verify(nativeClient).cancelInstrumentSubscriptions(Instrument.MCL);
    }

    /**
     * Legacy fallback: when the registry is empty (bootstrap not yet run), resolve()
     * falls back to refresh() which picks min(expiry) from IBKR results. This path
     * should be rare in prod.
     */
    @Test
    void resolve_fallsBackToMinExpiry_whenRegistryEmpty() {
        when(registry.getContractMonth(Instrument.MCL)).thenReturn(Optional.empty());

        Contract clk26 = new Contract();
        clk26.lastTradeDateOrContractMonth("20260421");
        clk26.conid(111);
        ContractDetails clk26Details = new ContractDetails();
        clk26Details.contract(clk26);

        Contract clm26 = new Contract();
        clm26.lastTradeDateOrContractMonth("20260519");
        clm26.conid(222);
        ContractDetails clm26Details = new ContractDetails();
        clm26Details.contract(clm26);

        when(nativeClient.requestContractDetails(any(Contract.class)))
            .thenReturn(List.of(clk26Details, clm26Details));

        Optional<IbGatewayResolvedContract> result = resolver.resolve(Instrument.MCL);

        // refresh() picks min(expiry) → CLK26 (legacy behaviour).
        assertThat(result).isPresent();
        assertThat(result.get().contract().conid()).isEqualTo(111);

        // Registry empty ⇒ no cancelInstrumentSubscriptions call from refreshToMonth().
        verify(nativeClient, never()).cancelInstrumentSubscriptions(any());
    }

    /**
     * Cache hit: resolve() must not consult the registry or IBKR when a cached
     * contract is present.
     */
    @Test
    void resolve_usesCache_whenPresent() {
        Contract cached = new Contract();
        cached.lastTradeDateOrContractMonth("20260519");
        cached.conid(222);
        ContractDetails cachedDetails = new ContractDetails();
        cachedDetails.contract(cached);

        IbGatewayResolvedContract seed = new IbGatewayResolvedContract(Instrument.MCL, cached, cachedDetails);
        resolver.setResolved(Instrument.MCL, seed);

        // The registry stub below would be invoked if the cache were ignored.
        lenient().when(registry.getContractMonth(Instrument.MCL)).thenReturn(Optional.of("202605"));

        Optional<IbGatewayResolvedContract> result = resolver.resolve(Instrument.MCL);

        assertThat(result).isPresent();
        assertThat(result.get().contract().conid()).isEqualTo(222);

        // Cache hit ⇒ no IBKR call at all.
        verify(nativeClient, never()).requestContractDetails(any());
    }
}
