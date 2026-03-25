package com.riskdesk.application.service;

import com.riskdesk.presentation.dto.IbkrAuthStatusView;
import com.riskdesk.presentation.dto.IbkrPortfolioSnapshot;

public interface IbkrBrokerGateway {

    IbkrPortfolioSnapshot getPortfolio(String requestedAccountId);

    IbkrAuthStatusView getAuthStatus();

    IbkrAuthStatusView refreshAuthStatus();

    String backendName();
}
