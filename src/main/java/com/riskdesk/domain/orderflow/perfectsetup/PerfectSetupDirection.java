package com.riskdesk.domain.orderflow.perfectsetup;

/**
 * Direction of a Perfect Setup confluence signal.
 *
 * <p>{@link #NONE} means the order-flow axes do not agree on a tradable
 * direction (e.g. LONG and SHORT score equally) — the detector stays idle.</p>
 */
public enum PerfectSetupDirection {
    LONG,
    SHORT,
    NONE
}
