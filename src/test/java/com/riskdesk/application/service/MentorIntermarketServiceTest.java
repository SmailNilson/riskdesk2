package com.riskdesk.application.service;

import com.riskdesk.application.dto.MentorIntermarketSnapshot;
import com.riskdesk.domain.marketdata.model.DxySnapshot;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MentorIntermarketServiceTest {

    @Test
    void current_includesDxyChangeForDollarSensitiveInstrument() {
        DxyMarketService dxyMarketService = mock(DxyMarketService.class);
        MentorIntermarketService service = new MentorIntermarketService(dxyMarketService);

        DxySnapshot current = snapshot("2026-03-26T00:06:00Z", "104.250000");
        DxySnapshot baseline = snapshot("2026-03-25T23:50:00Z", "103.500000");

        when(dxyMarketService.latestSnapshot()).thenReturn(Optional.of(current));
        when(dxyMarketService.findBaselineSnapshot(Instant.parse("2026-03-26T00:06:00Z")))
            .thenReturn(Optional.of(baseline));

        MentorIntermarketSnapshot snapshot = service.current(Instrument.MGC);

        assertThat(snapshot.dxyPctChange()).isEqualTo(0.725);
        assertThat(snapshot.dxyTrend()).isEqualTo("BULLISH");
        assertThat(snapshot.metalsConvergenceStatus()).isEqualTo("DXY_AVAILABLE");
    }

    @Test
    void current_returnsUnavailableWhenNoBaselineExists() {
        DxyMarketService dxyMarketService = mock(DxyMarketService.class);
        MentorIntermarketService service = new MentorIntermarketService(dxyMarketService);

        when(dxyMarketService.latestSnapshot()).thenReturn(Optional.of(snapshot("2026-03-26T00:06:00Z", "104.250000")));
        when(dxyMarketService.findBaselineSnapshot(Instant.parse("2026-03-26T00:06:00Z")))
            .thenReturn(Optional.empty());

        MentorIntermarketSnapshot snapshot = service.current(Instrument.MNQ);

        assertThat(snapshot.dxyPctChange()).isNull();
        assertThat(snapshot.dxyTrend()).isEqualTo("UNAVAILABLE");
        assertThat(snapshot.metalsConvergenceStatus()).isEqualTo("UNAVAILABLE");
    }

    private static DxySnapshot snapshot(String timestamp, String value) {
        return new DxySnapshot(
            Instant.parse(timestamp),
            new BigDecimal("1.08110"),
            new BigDecimal("149.22000"),
            new BigDecimal("1.26220"),
            new BigDecimal("1.35120"),
            new BigDecimal("10.48050"),
            new BigDecimal("0.90215"),
            new BigDecimal(value),
            "IBKR_SYNTHETIC",
            true
        );
    }
}
