package com.riskdesk.application.dto;

/**
 * A point-in-time view of a broker order looked up by its {@code orderRef} (the WTX
 * {@code executionKey}). Returned by {@link com.riskdesk.application.service.IbkrBrokerGateway#findOrder}.
 *
 * <p>{@code status} is the raw IBKR order status string (e.g. {@code Submitted},
 * {@code PreSubmitted}, {@code Cancelled}, {@code ApiCancelled}, {@code Inactive},
 * {@code Filled}). Absent (empty {@link java.util.Optional}) means the order was found in
 * neither the live nor the completed set — gone, or the gateway could not be queried.
 */
public record BrokerOrderStatusView(Long orderId, String orderRef, String accountId, String status) {
}
