package com.riskdesk.domain.execution.port;

import com.riskdesk.domain.execution.MarketableExecutionSettings;

/**
 * Read access to the current {@link MarketableExecutionSettings} for the execution core. {@code
 * DefaultOrderRouter} reads this at order time so an operator toggle (UI → REST → persisted state) takes
 * effect live, without a restart. Implementations must be cheap to call on the hot path (cached, not a DB
 * hit per order). Functional interface so tests can pass a fixed-settings lambda.
 */
@FunctionalInterface
public interface MarketableSettingsProvider {

    /** The settings in effect right now — never null. */
    MarketableExecutionSettings current();
}
