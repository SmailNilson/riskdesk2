package com.riskdesk.application.service;

import com.riskdesk.application.dto.BrokerCancelResult;
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
     * Cancels a working broker order by its broker order id and reports the outcome. Used to replace a
     * still-resting WTX entry when a new opposite signal arrives (cancel the stale order, then submit the
     * fresh one) instead of skipping the new signal indefinitely. The result keeps {@code UNAVAILABLE} /
     * {@code FAILED} (order may still be live) distinct from {@code CANCELLED} / {@code NOT_FOUND} /
     * {@code ALREADY_INACTIVE} (nothing resting — safe to replace). Default: {@code UNAVAILABLE}
     * (gateway has no cancel capability).
     */
    default BrokerCancelResult cancelOrder(String requestedAccountId, long orderId) {
        return BrokerCancelResult.UNAVAILABLE;
    }
}
