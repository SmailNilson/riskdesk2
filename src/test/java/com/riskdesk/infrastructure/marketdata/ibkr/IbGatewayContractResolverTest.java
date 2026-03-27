package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IbGatewayContractResolverTest {

    @Test
    void skipsFrontMonthInsideCloseOutBufferForDeliveryContracts() {
        IbGatewayNativeClient nativeClient = mock(IbGatewayNativeClient.class);
        Clock clock = Clock.fixed(Instant.parse("2026-03-27T10:15:00Z"), ZoneOffset.UTC);
        IbGatewayContractResolver resolver = new IbGatewayContractResolver(nativeClient, clock);

        Contract april = contract("MGC", "COMEX", "20260428", 706903676);
        Contract june = contract("MGC", "COMEX", "20260626", 708000001);

        when(nativeClient.requestContractDetails(any())).thenReturn(List.of(details(april), details(june)));
        when(nativeClient.requestContractMarketSnapshot(any())).thenReturn(Optional.empty());

        Optional<IbGatewayResolvedContract> resolved = resolver.resolve(Instrument.MGC);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().contractMonth()).isEqualTo("202606");
        assertThat(resolved.get().contract().conid()).isEqualTo(708000001);
        assertThat(resolved.get().selectionReason()).contains("close-out buffer");
    }

    @Test
    void prefersMoreLiquidContractForLiquidityAwareInstruments() {
        IbGatewayNativeClient nativeClient = mock(IbGatewayNativeClient.class);
        Clock clock = Clock.fixed(Instant.parse("2026-03-27T10:15:00Z"), ZoneOffset.UTC);
        IbGatewayContractResolver resolver = new IbGatewayContractResolver(nativeClient, clock);

        Contract june = contract("MNQ", "CME", "20260619", 770561201);
        Contract september = contract("MNQ", "CME", "20260918", 770561202);

        when(nativeClient.requestContractDetails(any())).thenReturn(List.of(details(june), details(september)));
        when(nativeClient.requestContractMarketSnapshot(june)).thenReturn(Optional.of(
            new IbGatewayContractMarketSnapshot(
                new BigDecimal("21250.25"),
                new BigDecimal("21250.00"),
                new BigDecimal("21251.00"),
                2_000L)));
        when(nativeClient.requestContractMarketSnapshot(september)).thenReturn(Optional.of(
            new IbGatewayContractMarketSnapshot(
                new BigDecimal("21260.25"),
                new BigDecimal("21260.00"),
                new BigDecimal("21260.25"),
                120_000L)));

        Optional<IbGatewayResolvedContract> resolved = resolver.resolve(Instrument.MNQ);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().contractMonth()).isEqualTo("202609");
        assertThat(resolved.get().contract().conid()).isEqualTo(770561202);
        assertThat(resolved.get().selectionReason()).contains("stronger live liquidity");
    }

    @Test
    void fallsBackToPreconfiguredContractWhenDiscoveryFails() {
        IbGatewayNativeClient nativeClient = mock(IbGatewayNativeClient.class);
        Clock clock = Clock.fixed(Instant.parse("2026-03-27T10:15:00Z"), ZoneOffset.UTC);
        IbGatewayContractResolver resolver = new IbGatewayContractResolver(nativeClient, clock);

        when(nativeClient.requestContractDetails(any())).thenReturn(List.of());

        Optional<IbGatewayResolvedContract> resolved = resolver.resolve(Instrument.E6);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().contractMonth()).isEqualTo("202606");
        assertThat(resolved.get().selectionReason()).isEqualTo("preconfigured fallback");
    }

    private static ContractDetails details(Contract contract) {
        ContractDetails details = new ContractDetails();
        details.contract(contract);
        return details;
    }

    private static Contract contract(String symbol, String exchange, String lastTradeDate, int conid) {
        Contract contract = new Contract();
        contract.symbol(symbol);
        contract.exchange(exchange);
        contract.currency("USD");
        contract.lastTradeDateOrContractMonth(lastTradeDate);
        contract.conid(conid);
        return contract;
    }
}
