package com.riskdesk.application.dto;

import com.riskdesk.domain.execution.MarketableExecutionSettings;

/** Read view of the runtime marketable-execution policy (REST response). */
public record MarketableSettingsView(boolean closeEnabled, boolean reverseOpenEnabled, int crossTicks) {

    public static MarketableSettingsView from(MarketableExecutionSettings s) {
        return new MarketableSettingsView(s.closeEnabled(), s.reverseOpenEnabled(), s.crossTicks());
    }
}
