package com.riskdesk.domain.shared;

import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TradingSessionResolverTest {

    private static final ZoneId ET = TradingSessionResolver.CME_ZONE;

    @AfterEach
    void cleanupDynamicSchedules() {
        TradingSessionResolver.clearAllSchedules();
    }

    @Test
    void dynamicSchedule_earlyClose_MNQClosesAt1300_MGCStillOpen() {
        Instant mnqOpen = ZonedDateTime.of(2023, 11, 24, 0, 0, 0, 0, ET).toInstant();
        Instant mnqClose = ZonedDateTime.of(2023, 11, 24, 13, 0, 0, 0, ET).toInstant();
        TradingSessionResolver.registerSchedule(Instrument.MNQ,
                List.of(new TradingInterval(mnqOpen, mnqClose)));

        Instant mgcOpen = ZonedDateTime.of(2023, 11, 24, 0, 0, 0, 0, ET).toInstant();
        Instant mgcClose = ZonedDateTime.of(2023, 11, 24, 14, 30, 0, 0, ET).toInstant();
        TradingSessionResolver.registerSchedule(Instrument.MGC,
                List.of(new TradingInterval(mgcOpen, mgcClose)));

        Instant at1330 = ZonedDateTime.of(2023, 11, 24, 13, 30, 0, 0, ET).toInstant();
        assertTrue(TradingSessionResolver.isMaintenanceWindow(at1330, Instrument.MNQ));
        assertFalse(TradingSessionResolver.isMaintenanceWindow(at1330, Instrument.MGC));

        Instant at1200 = ZonedDateTime.of(2023, 11, 24, 12, 0, 0, 0, ET).toInstant();
        assertFalse(TradingSessionResolver.isMaintenanceWindow(at1200, Instrument.MNQ));
        assertFalse(TradingSessionResolver.isMaintenanceWindow(at1200, Instrument.MGC));

        Instant at1500 = ZonedDateTime.of(2023, 11, 24, 15, 0, 0, 0, ET).toInstant();
        assertTrue(TradingSessionResolver.isMaintenanceWindow(at1500, Instrument.MNQ));
        assertTrue(TradingSessionResolver.isMaintenanceWindow(at1500, Instrument.MGC));
    }

    @Test
    void dynamicSchedule_noSchedule_fallsBackToDefaultCmeRules() {
        Instant regularSession = ZonedDateTime.of(2026, 3, 25, 10, 0, 0, 0, ET).toInstant();
        assertFalse(TradingSessionResolver.isMaintenanceWindow(regularSession, Instrument.MCL));

        Instant maintenance = ZonedDateTime.of(2026, 3, 25, 17, 30, 0, 0, ET).toInstant();
        assertTrue(TradingSessionResolver.isMaintenanceWindow(maintenance, Instrument.MCL));
    }

    @Test
    void dynamicSchedule_nullInstrument_fallsBackToDefaultCmeRules() {
        Instant regularSession = ZonedDateTime.of(2026, 3, 25, 10, 0, 0, 0, ET).toInstant();
        assertFalse(TradingSessionResolver.isMaintenanceWindow(regularSession, null));

        Instant maintenance = ZonedDateTime.of(2026, 3, 25, 17, 30, 0, 0, ET).toInstant();
        assertTrue(TradingSessionResolver.isMaintenanceWindow(maintenance, null));
    }

    @Test
    void dynamicSchedule_closedDay_treatedAsMaintenance() {
        String raw = "20231123:CLOSED;20231124:0930-1315";
        List<TradingInterval> intervals = TradingHoursParser.parse(raw, "US/Eastern");
        TradingSessionResolver.registerSchedule(Instrument.MNQ, intervals);

        Instant openTime = ZonedDateTime.of(2023, 11, 24, 10, 0, 0, 0, ET).toInstant();
        assertFalse(TradingSessionResolver.isMaintenanceWindow(openTime, Instrument.MNQ));

        Instant closed = ZonedDateTime.of(2023, 11, 24, 14, 0, 0, 0, ET).toInstant();
        assertTrue(TradingSessionResolver.isMaintenanceWindow(closed, Instrument.MNQ));
    }

    @Test
    void clearSchedule_removesRegistration() {
        Instant open = ZonedDateTime.of(2023, 11, 24, 9, 30, 0, 0, ET).toInstant();
        Instant close = ZonedDateTime.of(2023, 11, 24, 13, 0, 0, 0, ET).toInstant();
        TradingSessionResolver.registerSchedule(Instrument.MNQ,
                List.of(new TradingInterval(open, close)));

        TradingSessionResolver.clearSchedule(Instrument.MNQ);

        Instant at1330 = ZonedDateTime.of(2023, 11, 24, 13, 30, 0, 0, ET).toInstant();
        assertFalse(TradingSessionResolver.isMaintenanceWindow(at1330, Instrument.MNQ),
                "After clearSchedule, should fall back to CME rules (Fri 13:30 = open)");
    }

    @Test
    void defaultCmeRules_saturday_isMaintenance() {
        Instant saturday = ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, ET).toInstant();
        assertTrue(TradingSessionResolver.isMaintenanceWindow(saturday, Instrument.MCL));
    }

    @Test
    void defaultCmeRules_sundayAfterOpen_isNotMaintenance() {
        Instant sundayAfter = ZonedDateTime.of(2026, 3, 22, 18, 0, 0, 0, ET).toInstant();
        assertFalse(TradingSessionResolver.isMaintenanceWindow(sundayAfter, Instrument.MCL));
    }
}
