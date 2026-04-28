package com.riskdesk.domain.externalsetup;

/**
 * Lifecycle of an externally-submitted trade setup.
 *
 * <pre>
 *   PENDING ──▶ VALIDATED ──▶ EXECUTED   (user clicks ✅, broker accepts entry)
 *      │                  │
 *      │                  ╰─▶ EXECUTION_FAILED  (broker rejected)
 *      ├──▶ REJECTED                            (user clicks ❌)
 *      ╰──▶ EXPIRED                             (TTL elapsed without action)
 * </pre>
 */
public enum ExternalSetupStatus {
    PENDING,
    VALIDATED,
    EXECUTED,
    EXECUTION_FAILED,
    REJECTED,
    EXPIRED
}
