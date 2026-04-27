package com.riskdesk.application.service;

/**
 * Command for arming a trade directly from an order-flow signal.
 * Maps from the REST request DTO; kept in the application layer so the domain
 * model stays decoupled from presentation concerns.
 */
public record OrderFlowQuickExecutionCommand(
    String instrument,
    String timeframe,
    String action,
    double entryPrice,
    double stopLoss,
    double takeProfit,
    int quantity,
    String brokerAccountId,
    String reason
) {
}
