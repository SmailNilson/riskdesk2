package com.riskdesk.application.service;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.dto.IbkrAuthStatusView;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;

public interface IbkrBrokerGateway {

    IbkrPortfolioSnapshot getPortfolio(String requestedAccountId);

    IbkrAuthStatusView getAuthStatus();

    IbkrAuthStatusView refreshAuthStatus();

    String backendName();

    BrokerEntryOrderSubmission submitEntryOrder(BrokerEntryOrderRequest request);
}
