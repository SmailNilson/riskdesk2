package com.riskdesk.infrastructure.marketdata.ibkr;

public record IbGatewayContractSelectionPolicy(
    int minimumDaysToExpiry,
    int liquidityProbeCount,
    IbGatewayContractSelectionPreference preference
) {
}
