package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link IbGatewayHistoricalProvider#rangeContractWalk} — the window-derived
 * expired-contract walk depth that lets multi-month 1m range backfills reach far enough back.
 */
class IbGatewayHistoricalProviderWalkTest {

    private static Instant from(int daysAgo) {
        return Instant.parse("2026-06-09T00:00:00Z").minus(daysAgo, ChronoUnit.DAYS);
    }

    private static final Instant TO = Instant.parse("2026-06-09T00:00:00Z");

    @Test
    void quarterly_90DayWindow_reachesTwoContractsBack() {
        // MNQ rolls quarterly (~90d/contract): 90/90 + 2 = 3 walks → front + 2 quarters.
        assertEquals(3, IbGatewayHistoricalProvider.rangeContractWalk(Instrument.MNQ, from(90), TO));
    }

    @Test
    void quarterly_sixMonthWindow_walksDeeper() {
        // 180/90 + 2 = 4 walks — enough to cover ~6 months of MNQ across contracts.
        assertEquals(4, IbGatewayHistoricalProvider.rangeContractWalk(Instrument.MNQ, from(180), TO));
    }

    @Test
    void monthly_scalesWithWindow() {
        // MCL rolls monthly (~30d/contract): 120/30 + 2 = 6 walks.
        assertEquals(6, IbGatewayHistoricalProvider.rangeContractWalk(Instrument.MCL, from(120), TO));
    }

    @Test
    void shortWindow_stillKeepsBuffer() {
        // A tiny window must still allow at least the +2 boundary buffer.
        assertEquals(2, IbGatewayHistoricalProvider.rangeContractWalk(Instrument.MNQ, from(1), TO));
    }

    @Test
    void walkIsCappedAtSafetyMaximum() {
        // An absurd window must not exceed the hard cap (MAX_CONTRACT_WALK = 24).
        int walk = IbGatewayHistoricalProvider.rangeContractWalk(Instrument.MCL, from(5000), TO);
        assertTrue(walk <= 24, "walk must be capped at the safety maximum, was " + walk);
    }
}
