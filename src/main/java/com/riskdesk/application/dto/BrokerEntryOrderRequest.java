package com.riskdesk.application.dto;

import java.math.BigDecimal;

/**
 * Broker entry-order request. {@code orderType} is {@code "LIMIT"} (price on
 * {@code limitPrice}) or {@code "STOP"} (trigger on {@code stopPrice}). The legacy
 * 7-arg constructor defaults to a LIMIT order so existing callers are unchanged;
 * only the PLAYBOOK confirmation profile submits STOP entries.
 */
public record BrokerEntryOrderRequest(
    Long executionId,
    String executionKey,
    String brokerAccountId,
    String instrument,
    String action,
    Integer quantity,
    BigDecimal limitPrice,
    String orderType,
    BigDecimal stopPrice
) {
    public static final String ORDER_TYPE_LIMIT = "LIMIT";
    public static final String ORDER_TYPE_STOP = "STOP";

    /** Legacy LIMIT-order constructor — unchanged behaviour for existing callers. */
    public BrokerEntryOrderRequest(Long executionId, String executionKey, String brokerAccountId,
                                   String instrument, String action, Integer quantity, BigDecimal limitPrice) {
        this(executionId, executionKey, brokerAccountId, instrument, action, quantity,
            limitPrice, ORDER_TYPE_LIMIT, null);
    }

    public boolean isStop() {
        return ORDER_TYPE_STOP.equalsIgnoreCase(orderType);
    }
}
