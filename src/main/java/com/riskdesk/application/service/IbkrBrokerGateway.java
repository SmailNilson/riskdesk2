package com.riskdesk.application.service;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.dto.BrokerOrderLookup;
import com.riskdesk.application.dto.IbkrAuthStatusView;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;

public interface IbkrBrokerGateway {

    IbkrPortfolioSnapshot getPortfolio(String requestedAccountId);

    IbkrAuthStatusView getAuthStatus();

    IbkrAuthStatusView refreshAuthStatus();

    String backendName();

    BrokerEntryOrderSubmission submitEntryOrder(BrokerEntryOrderRequest request);

    /**
     * Looks up a broker order by its {@code orderRef} (the WTX {@code executionKey}), checking the
     * live order book first and then the completed/historical orders. Used to reconcile a stuck
     * {@code ENTRY_SUBMITTED} tracking row against the broker's truth. The tri-state result keeps
     * {@code UNAVAILABLE} (couldn't query) distinct from {@code NOT_FOUND} (queried, absent) so a
     * caller never mistakes an outage for absence. Default: {@code UNAVAILABLE} (gateway has no
     * order lookup).
     */
    default BrokerOrderLookup findOrder(String requestedAccountId, String orderRef) {
        return BrokerOrderLookup.unavailable();
    }

    /**
     * Cancels a working broker order by its IBKR order id (the {@code ibkrOrderId} persisted on the
     * execution row at submit time). Returns the broker's first cancel feedback ({@code Cancelled} /
     * {@code PendingCancel} / {@code CancelRequested}); the row itself is finalized asynchronously by
     * the {@code Cancelled} orderStatus callback through {@code ExecutionFillTrackingService}.
     * Default: unsupported (gateway has no order cancellation).
     */
    default String cancelOrder(int ibkrOrderId) {
        throw new UnsupportedOperationException(backendName() + " does not support order cancellation");
    }
}
