package com.riskdesk.application.service;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.dto.BrokerOrderStatusView;
import com.riskdesk.application.dto.IbkrAuthStatusView;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;

import java.util.Optional;

public interface IbkrBrokerGateway {

    IbkrPortfolioSnapshot getPortfolio(String requestedAccountId);

    IbkrAuthStatusView getAuthStatus();

    IbkrAuthStatusView refreshAuthStatus();

    String backendName();

    BrokerEntryOrderSubmission submitEntryOrder(BrokerEntryOrderRequest request);

    /**
     * Looks up a broker order by its {@code orderRef} (the WTX {@code executionKey}), checking the
     * live order book first and then the completed/historical orders. Used to reconcile a stuck
     * {@code ENTRY_SUBMITTED} tracking row against the broker's truth. Returns empty when the order
     * is in neither set, or when this gateway does not support order lookup. Default: empty.
     */
    default Optional<BrokerOrderStatusView> findOrder(String requestedAccountId, String orderRef) {
        return Optional.empty();
    }
}
