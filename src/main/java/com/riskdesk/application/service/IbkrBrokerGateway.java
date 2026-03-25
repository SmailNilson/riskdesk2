package com.riskdesk.application.service;

import com.riskdesk.application.dto.IbkrAuthStatusView;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;

public interface IbkrBrokerGateway {

    IbkrPortfolioSnapshot getPortfolio(String requestedAccountId);

    IbkrAuthStatusView getAuthStatus();

    IbkrAuthStatusView refreshAuthStatus();

    String backendName();
}
