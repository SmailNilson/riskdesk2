package com.riskdesk.application.dto;

import java.math.BigDecimal;

/**
 * Broker entry-order request. {@code orderType} is one of:
 * <ul>
 *   <li>{@code "LIMIT"} — price on {@code limitPrice} ({@code stopPrice} null);</li>
 *   <li>{@code "STOP"} — market trigger on {@code stopPrice} ({@code limitPrice} null);</li>
 *   <li>{@code "STOP_LIMIT"} — trigger on {@code stopPrice}, fill capped at {@code limitPrice}.</li>
 * </ul>
 * The legacy 7-arg constructor defaults to a LIMIT order so existing callers are unchanged. The
 * PLAYBOOK confirmation profile submits STOP_LIMIT entries: the STOP triggers on the zone break,
 * the LIMIT caps how far the fill may slip past it (a plain STOP becomes a market order and slips
 * uncapped on a fast break).
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
    public static final String ORDER_TYPE_STOP_LIMIT = "STOP_LIMIT";

    /** Legacy LIMIT-order constructor — unchanged behaviour for existing callers. */
    public BrokerEntryOrderRequest(Long executionId, String executionKey, String brokerAccountId,
                                   String instrument, String action, Integer quantity, BigDecimal limitPrice) {
        this(executionId, executionKey, brokerAccountId, instrument, action, quantity,
            limitPrice, ORDER_TYPE_LIMIT, null);
    }

    /** STOP-LIMIT entry: {@code triggerPrice} arms the order, {@code limitCap} bounds the fill. */
    public static BrokerEntryOrderRequest stopLimit(Long executionId, String executionKey, String brokerAccountId,
                                                    String instrument, String action, Integer quantity,
                                                    BigDecimal triggerPrice, BigDecimal limitCap) {
        return new BrokerEntryOrderRequest(executionId, executionKey, brokerAccountId, instrument, action,
            quantity, limitCap, ORDER_TYPE_STOP_LIMIT, triggerPrice);
    }

    /** True for a plain market STOP only (not STOP_LIMIT). */
    public boolean isStop() {
        return ORDER_TYPE_STOP.equalsIgnoreCase(orderType);
    }

    public boolean isStopLimit() {
        return ORDER_TYPE_STOP_LIMIT.equalsIgnoreCase(orderType);
    }
}
