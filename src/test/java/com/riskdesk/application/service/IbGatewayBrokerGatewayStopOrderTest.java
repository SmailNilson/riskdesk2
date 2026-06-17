package com.riskdesk.application.service;

import com.ib.client.Contract;
import com.ib.client.Types.Action;
import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayContractResolver;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayNativeClient;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayNativeClient.NativeOrderSubmission;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayResolvedContract;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the order-type dispatch added for the PLAYBOOK confirmation profile: a STOP
 * entry request must submit an IBKR STOP order (trigger on the stop price), a LIMIT
 * request a LIMIT order. A buy-limit at zoneHigh above market would fill immediately —
 * the opposite of the breakout entry — so this routing must never regress.
 */
class IbGatewayBrokerGatewayStopOrderTest {

    private final IbGatewayNativeClient nativeClient = mock(IbGatewayNativeClient.class);
    private final IbGatewayContractResolver resolver = mock(IbGatewayContractResolver.class);
    private final IbkrProperties props = new IbkrProperties();
    private final IbGatewayBrokerGateway gateway = new IbGatewayBrokerGateway(nativeClient, resolver, props);

    private final NativeOrderSubmission ack =
        new NativeOrderSubmission(1L, "Submitted", "ref", Instant.now());

    private void resolveMnq() {
        when(resolver.resolve(Instrument.MNQ)).thenReturn(
            Optional.of(new IbGatewayResolvedContract(Instrument.MNQ, new Contract(), null)));
    }

    @Test
    void stopRequestSubmitsStopOrderWithTriggerPrice() {
        resolveMnq();
        when(nativeClient.placeStopOrder(any(), any(), any(), eq(2), eq(new BigDecimal("29710")), any()))
            .thenReturn(ack);

        gateway.submitEntryOrder(new BrokerEntryOrderRequest(
            10L, "ek", "DU123", "MNQ", "LONG", 2,
            null, BrokerEntryOrderRequest.ORDER_TYPE_STOP, new BigDecimal("29710")));

        verify(nativeClient).placeStopOrder(any(), eq("DU123"), eq(Action.BUY), eq(2),
            eq(new BigDecimal("29710")), eq("ek"));
        verify(nativeClient, never()).placeLimitOrder(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void shortStopMapsToSellStop() {
        resolveMnq();
        when(nativeClient.placeStopOrder(any(), any(), eq(Action.SELL), eq(1), any(), any())).thenReturn(ack);

        gateway.submitEntryOrder(new BrokerEntryOrderRequest(
            11L, "ek2", "DU123", "MNQ", "SHORT", 1,
            null, BrokerEntryOrderRequest.ORDER_TYPE_STOP, new BigDecimal("29680")));

        verify(nativeClient).placeStopOrder(any(), eq("DU123"), eq(Action.SELL), eq(1),
            eq(new BigDecimal("29680")), eq("ek2"));
    }

    @Test
    void stopLimitRequestSubmitsStopLimitOrderWithTriggerAndCap() {
        resolveMnq();
        when(nativeClient.placeStopLimitOrder(any(), any(), eq(Action.SELL), eq(1),
            eq(new BigDecimal("30408.50")), eq(new BigDecimal("30403.50")), any())).thenReturn(ack);

        // SHORT confirmation: sell-stop trigger at the zone break, fill capped just below it.
        gateway.submitEntryOrder(BrokerEntryOrderRequest.stopLimit(
            13L, "ek4", "DU123", "MNQ", "SHORT", 1,
            new BigDecimal("30408.50"), new BigDecimal("30403.50")));

        verify(nativeClient).placeStopLimitOrder(any(), eq("DU123"), eq(Action.SELL), eq(1),
            eq(new BigDecimal("30408.50")), eq(new BigDecimal("30403.50")), eq("ek4"));
        verify(nativeClient, never()).placeStopOrder(any(), any(), any(), anyInt(), any(), any());
        verify(nativeClient, never()).placeLimitOrder(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void legacyLimitRequestStillSubmitsLimitOrder() {
        resolveMnq();
        when(nativeClient.placeLimitOrder(any(), any(), any(), anyInt(), any(), any())).thenReturn(ack);

        gateway.submitEntryOrder(new BrokerEntryOrderRequest(
            12L, "ek3", "DU123", "MNQ", "LONG", 3, new BigDecimal("29600")));

        verify(nativeClient).placeLimitOrder(any(), eq("DU123"), eq(Action.BUY), eq(3),
            eq(new BigDecimal("29600")), eq("ek3"));
        verify(nativeClient, never()).placeStopOrder(any(), any(), any(), anyInt(), any(), any());
    }
}
