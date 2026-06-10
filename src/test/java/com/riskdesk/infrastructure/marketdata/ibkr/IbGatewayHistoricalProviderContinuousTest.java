package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.Types.SecType;
import com.ib.controller.Bar;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link IbGatewayHistoricalProvider#fetchContinuousHistoryRange} — the CONTFUT
 * (continuous contract) range fetch. The IBKR request must carry the resolver's CONTFUT contract
 * (never the cached front month) and the streamed candles must be tagged {@code CONT} so their
 * provenance stays distinguishable from single-contract rows.
 */
@ExtendWith(MockitoExtension.class)
class IbGatewayHistoricalProviderContinuousTest {

    @Mock private IbGatewayNativeClient nativeClient;
    @Mock private IbGatewayContractResolver contractResolver;

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO   = Instant.parse("2026-01-01T00:05:00Z");

    @Test
    void continuousRange_requestsTheContfutContract_andTagsCandlesCont() {
        IbGatewayHistoricalProvider provider = new IbGatewayHistoricalProvider(nativeClient, contractResolver);

        Contract contfut = new Contract();
        contfut.secType(SecType.CONTFUT);
        contfut.symbol("MNQ");
        contfut.exchange("CME");
        when(contractResolver.continuousContract(Instrument.MNQ)).thenReturn(Optional.of(contfut));

        // One bar at the window's lower bound — the backward walk stops after the first chunk.
        Bar bar = new Bar(FROM.getEpochSecond(), 101.0, 99.0, 100.0, 100.5,
            Decimal.get(100.0), Decimal.get(5), 1);
        when(nativeClient.requestHistoricalBars(same(contfut), any(Instant.class), anyInt(), any(), any(), any(), anyBoolean()))
            .thenReturn(List.of(bar));

        List<Candle> received = new ArrayList<>();
        int total = provider.fetchContinuousHistoryRange(Instrument.MNQ, "1m", FROM, TO, received::addAll);

        assertThat(total).isEqualTo(1);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).getContractMonth()).isEqualTo(IbGatewayHistoricalProvider.CONTINUOUS_MONTH_TAG);
        assertThat(received.get(0).getTimestamp()).isEqualTo(FROM);
        // The front-month resolution path (live cache) must stay untouched.
        verify(contractResolver, never()).resolve(any());
    }

    @Test
    void continuousRange_isZeroWhenNoContfutContract() {
        IbGatewayHistoricalProvider provider = new IbGatewayHistoricalProvider(nativeClient, contractResolver);
        when(contractResolver.continuousContract(Instrument.MNQ)).thenReturn(Optional.empty());

        int total = provider.fetchContinuousHistoryRange(Instrument.MNQ, "1m", FROM, TO, c -> { });

        assertThat(total).isZero();
        verify(nativeClient, never()).requestHistoricalBars(any(), any(), anyInt(), any(), any(), any(), anyBoolean());
    }

    @Test
    void continuousRange_rejectsInvalidWindow() {
        IbGatewayHistoricalProvider provider = new IbGatewayHistoricalProvider(nativeClient, contractResolver);

        int total = provider.fetchContinuousHistoryRange(Instrument.MNQ, "1m", TO, FROM, c -> { });

        assertThat(total).isZero();
        verify(nativeClient, never()).requestHistoricalBars(any(), any(), anyInt(), any(), any(), any(), anyBoolean());
    }
}
