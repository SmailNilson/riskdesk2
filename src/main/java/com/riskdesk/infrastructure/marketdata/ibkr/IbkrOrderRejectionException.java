package com.riskdesk.infrastructure.marketdata.ibkr;

/**
 * Typed runtime exception raised by {@link IbGatewayNativeClient} when an IBKR order
 * cannot be placed (or its acknowledgement is not received in time).
 *
 * <p>The {@link Kind} discriminates the four cases the bridge needs to react to
 * differently:</p>
 * <ul>
 *   <li>{@link Kind#TIMEOUT} — IBKR did not acknowledge the order within the
 *       configured wait window. The broker state is <b>unknown</b>: the order may
 *       have been received but the {@code orderStatus} callback was lost.
 *       Callers should not assume the order is dead and must avoid double-flatten.</li>
 *   <li>{@link Kind#INSUFFICIENT_MARGIN} — IBKR reported an explicit margin /
 *       equity / buying-power error (typically error code 201). The order is rejected
 *       and no position change occurred at the broker.</li>
 *   <li>{@link Kind#BROKER_REJECT} — IBKR reported a non-margin rejection
 *       (status Cancelled / ApiCancelled / Inactive, or a {@code rejectReason}
 *       not matching the margin keywords).</li>
 *   <li>{@link Kind#CANCELLED} — order explicitly cancelled (alias of
 *       {@code BROKER_REJECT} kept for finer log routing if needed).</li>
 *   <li>{@link Kind#UNKNOWN} — fallback when none of the above match.</li>
 * </ul>
 *
 * <p>Carries the raw IBKR {@code errorCode} (nullable, e.g. {@code 201}) and the
 * broker-side message so the bridge can persist a human-readable reason and the
 * frontend can surface it in a tooltip without forcing the UI layer to parse
 * exception messages.</p>
 */
public class IbkrOrderRejectionException extends RuntimeException {

    public enum Kind {
        TIMEOUT,
        INSUFFICIENT_MARGIN,
        BROKER_REJECT,
        CANCELLED,
        UNKNOWN
    }

    private final Kind kind;
    private final Integer brokerErrorCode;
    private final String brokerMessage;
    private final Long brokerOrderId;

    public IbkrOrderRejectionException(Kind kind, Integer brokerErrorCode, String brokerMessage, String detail) {
        this(kind, brokerErrorCode, brokerMessage, detail, null);
    }

    public IbkrOrderRejectionException(Kind kind, Integer brokerErrorCode, String brokerMessage,
                                       String detail, Long brokerOrderId) {
        super(detail != null ? detail : (brokerMessage != null ? brokerMessage : "IBKR order rejected"));
        this.kind = kind == null ? Kind.UNKNOWN : kind;
        this.brokerErrorCode = brokerErrorCode;
        this.brokerMessage = brokerMessage;
        this.brokerOrderId = brokerOrderId;
    }

    public Kind kind() {
        return kind;
    }

    public Integer brokerErrorCode() {
        return brokerErrorCode;
    }

    public String brokerMessage() {
        return brokerMessage;
    }

    public Long brokerOrderId() {
        return brokerOrderId;
    }
}
