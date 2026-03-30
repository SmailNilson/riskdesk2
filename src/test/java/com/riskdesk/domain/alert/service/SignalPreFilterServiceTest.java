package com.riskdesk.domain.alert.service;

import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import com.riskdesk.domain.alert.model.AlertSeverity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignalPreFilterServiceTest {

    private final SignalPreFilterService service = new SignalPreFilterService();

    @Test
    void antiChop_blocksOppositeDirectionOnSameInstrumentAndTimeframe() {
        Alert longAlert = new Alert("ema:golden:MCL:10m", AlertSeverity.INFO,
                "MCL [10m] - EMA Golden Cross", AlertCategory.EMA, "MCL");
        Alert shortAlert = new Alert("ema:death:MCL:10m", AlertSeverity.WARNING,
                "MCL [10m] - EMA Death Cross", AlertCategory.EMA, "MCL");

        service.recordSignals(List.of(longAlert), "10m");

        List<Alert> filtered = service.filter(List.of(shortAlert), "10m", "UNDEFINED");

        assertEquals(0, filtered.size());
    }

    @Test
    void antiChop_doesNotMixDifferentTimeframes() {
        Alert longAlert = new Alert("ema:golden:MCL:10m", AlertSeverity.INFO,
                "MCL [10m] - EMA Golden Cross", AlertCategory.EMA, "MCL");
        Alert shortAlert = new Alert("ema:death:MCL:1h", AlertSeverity.WARNING,
                "MCL [1h] - EMA Death Cross", AlertCategory.EMA, "MCL");

        service.recordSignals(List.of(longAlert), "10m");

        List<Alert> filtered = service.filter(List.of(shortAlert), "1h", "UNDEFINED");

        assertEquals(1, filtered.size());
    }
}
