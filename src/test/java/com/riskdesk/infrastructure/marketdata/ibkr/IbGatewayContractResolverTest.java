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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
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
     * Regression test for the prod MCL outage (2026-06-09): refreshToMonth() ran while the
     * IB Gateway was unreachable, cached a synthetic conId=0 contract, and served it forever —
     * every subscription died with IBKR error 200 ("No security definition") until a lucky
     * restart. A provisional (conId=0) cache entry must be re-resolved on a later resolve()
     * call once IBKR responds, and live streams must be switched to the real contract.
     */
    @Test
    void resolve_retriesProvisionalContract_andSwitchesStreams() {
        when(registry.getContractMonth(Instrument.MCL)).thenReturn(Optional.of("202606"));

        // Phase 1: IBKR unreachable — requestContractDetails returns empty (ensureConnected failed).
        when(nativeClient.requestContractDetails(any(Contract.class))).thenReturn(List.of());

        Optional<IbGatewayResolvedContract> poisoned = resolver.resolve(Instrument.MCL);
        assertThat(poisoned).isPresent();
        assertThat(poisoned.get().contract().conid()).isZero(); // synthetic fallback
        assertThat(poisoned.get().contract().lastTradeDateOrContractMonth()).isEqualTo("202606");

        // Phase 2: gateway is now connected — IBKR returns the real July-delivery contract
        // (expiry 2026-06-22 → normalized month 202606 matches the registry target).
        Contract real = new Contract();
        real.lastTradeDateOrContractMonth("20260622");
        real.conid(333);
        ContractDetails realDetails = new ContractDetails();
        realDetails.contract(real);
        when(nativeClient.requestContractDetails(any(Contract.class))).thenReturn(List.of(realDetails));

        Optional<IbGatewayResolvedContract> healed = resolver.resolve(Instrument.MCL);

        assertThat(healed).isPresent();
        assertThat(healed.get().contract().conid()).isEqualTo(333);
        // Live price/quote/depth streams must be switched from the poisoned contract to the real one.
        verify(nativeClient).cancelAndResubscribe(
            same(poisoned.get().contract()), same(real), eq(Instrument.MCL));

        // Phase 3: healed contract is now a normal cache hit — no further IBKR traffic.
        clearInvocations(nativeClient);
        Optional<IbGatewayResolvedContract> cachedHit = resolver.resolve(Instrument.MCL);
        assertThat(cachedHit).isPresent();
        assertThat(cachedHit.get().contract().conid()).isEqualTo(333);
        verify(nativeClient, never()).requestContractDetails(any());
    }

    /**
     * The provisional retry is rate-limited: two resolve() calls in quick succession while
     * IBKR is still unreachable must not hammer requestContractDetails on every call.
     */
    @Test
    void resolve_rateLimitsProvisionalRetry() {
        when(registry.getContractMonth(Instrument.MCL)).thenReturn(Optional.of("202606"));
        when(nativeClient.requestContractDetails(any(Contract.class))).thenReturn(List.of());

        resolver.resolve(Instrument.MCL); // seeds the provisional contract (queries IBKR)
        clearInvocations(nativeClient);

        // First resolve() after seeding attempts one retry immediately (no prior attempt stamp)...
        resolver.resolve(Instrument.MCL);
        verify(nativeClient, atLeastOnce()).requestContractDetails(any(Contract.class));
        clearInvocations(nativeClient);

        // ...but a second resolve() right after is inside the retry interval: no IBKR call.
        Optional<IbGatewayResolvedContract> still = resolver.resolve(Instrument.MCL);
        assertThat(still).isPresent();
        assertThat(still.get().contract().conid()).isZero();
        verify(nativeClient, never()).requestContractDetails(any());
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
