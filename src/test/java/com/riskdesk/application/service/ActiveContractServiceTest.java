package com.riskdesk.application.service;

import com.ib.client.Contract;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayContractResolver;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayResolvedContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActiveContractServiceTest {

    @Test
    void derivesMonthCodeSymbolFromActiveContractMonth() {
        IbGatewayContractResolver resolver = mock(IbGatewayContractResolver.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<IbGatewayContractResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(resolver);

        Contract contract = new Contract();
        contract.localSymbol("MGC JUN 26");
        IbGatewayResolvedContract resolved = new IbGatewayResolvedContract(
            Instrument.MGC,
            contract,
            null,
            "202606",
            "selected nearest tradable month 202606"
        );
        when(resolver.resolve(Instrument.MGC)).thenReturn(Optional.of(resolved));

        ActiveContractService service = new ActiveContractService(provider);
        ActiveContractService.ActiveContractDescriptor descriptor = service.describe(Instrument.MGC);

        assertThat(descriptor.asset()).isEqualTo("MGC 202606");
        assertThat(descriptor.contractSymbol()).isEqualTo("MGCM6");
        assertThat(descriptor.localSymbol()).isEqualTo("MGC JUN 26");
    }

    @Test
    void derivesOctoberAndDecemberSymbolsCorrectly() {
        IbGatewayContractResolver resolver = mock(IbGatewayContractResolver.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<IbGatewayContractResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(resolver);

        when(resolver.resolve(Instrument.DXY)).thenReturn(Optional.of(new IbGatewayResolvedContract(
            Instrument.DXY,
            new Contract(),
            null,
            "202610",
            "selected 202610"
        )));

        ActiveContractService service = new ActiveContractService(provider);

        assertThat(service.describe(Instrument.DXY).contractSymbol()).isEqualTo("DXYV6");

        when(resolver.resolve(Instrument.DXY)).thenReturn(Optional.of(new IbGatewayResolvedContract(
            Instrument.DXY,
            new Contract(),
            null,
            "202612",
            "selected 202612"
        )));

        assertThat(service.describe(Instrument.DXY).contractSymbol()).isEqualTo("DXYZ6");
    }
}
