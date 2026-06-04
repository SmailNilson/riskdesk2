package com.riskdesk.domain.execution.port;

import com.riskdesk.domain.execution.MarketableExecutionSettings;

import java.util.Optional;

/**
 * Persistence port for the singleton {@link MarketableExecutionSettings} so an operator's runtime change
 * survives a restart (mirrors how the Auto-IBKR toggle is persisted). {@link #load()} is empty until the
 * operator first saves — the service then falls back to the configured {@code @Value} defaults.
 */
public interface MarketableSettingsRepositoryPort {

    /** The persisted settings, or empty when the operator has never changed them. */
    Optional<MarketableExecutionSettings> load();

    /** Persist the settings (singleton row), returning what was stored. */
    MarketableExecutionSettings save(MarketableExecutionSettings settings);
}
