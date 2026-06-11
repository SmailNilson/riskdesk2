package com.riskdesk.application.service;

import com.riskdesk.application.dto.VolumeProfileView;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Session selection, developing flag, naked-POC assembly and the once-per-minute
 * cache of {@link VolumeProfileService}. All instants are fixed; session boundaries
 * come from {@link com.riskdesk.domain.shared.TradingSessionResolver} (DST-aware).
 */
class VolumeProfileServiceTest {

    /** Wed 2026-04-15 11:00 ET (EDT, UTC-4) — inside RTH. */
    private static final Instant DURING_RTH = Instant.parse("2026-04-15T15:00:00Z");
    /** Wed 2026-04-15 08:00 ET — pre-market (before 09:30). */
    private static final Instant PRE_MARKET = Instant.parse("2026-04-15T12:00:00Z");
    /** Wed 2026-01-14 11:00 ET (EST, UTC-5) — inside RTH in winter. */
    private static final Instant DURING_RTH_WINTER = Instant.parse("2026-01-14T16:00:00Z");

    private CandleRepositoryPort candleRepository;
    private OrderFlowProperties properties;

    @BeforeEach
    void setUp() {
        candleRepository = mock(CandleRepositoryPort.class);
        properties = new OrderFlowProperties();
        properties.getVolumeProfile().setNakedPocLookbackSessions(3);
        when(candleRepository.findCandlesBetween(any(), any(), any(), any())).thenReturn(List.of());
    }

    private VolumeProfileService service(Instant fixedNow) {
        return new VolumeProfileService(candleRepository, properties,
            Clock.fixed(fixedNow, ZoneOffset.UTC));
    }

    private static Candle candle(Instant ts, double price, long volume) {
        return new Candle(Instrument.MNQ, "1m", ts,
            BigDecimal.valueOf(price), BigDecimal.valueOf(price + 0.5),
            BigDecimal.valueOf(price - 0.5), BigDecimal.valueOf(price), volume);
    }

    // ─── Session selection & developing flag ──────────────────────────────────

    @Test
    void duringRth_currentSessionIsToday_andDeveloping() {
        // Today's RTH so far: 09:30 ET (13:30Z) .. now (15:00Z)
        when(candleRepository.findCandlesBetween(eq(Instrument.MNQ), eq("1m"),
            eq(Instant.parse("2026-04-15T13:30:00Z")), eq(DURING_RTH.minusSeconds(1))))
            .thenReturn(List.of(candle(Instant.parse("2026-04-15T14:00:00Z"), 21000, 500)));

        VolumeProfileView view = service(DURING_RTH).getProfile(Instrument.MNQ);

        assertThat(view.instrument()).isEqualTo("MNQ");
        assertThat(view.session().date()).isEqualTo("2026-04-15");
        assertThat(view.session().developing()).isTrue();
        assertThat(view.session().poc()).isNotNull();
        assertThat(view.session().totalVolume()).isEqualTo(500);
    }

    @Test
    void duringRthWinter_estOffset_isUsedForTheWindow() {
        // EST: 09:30 ET = 14:30 UTC — DST-sensitive window bound
        when(candleRepository.findCandlesBetween(eq(Instrument.MNQ), eq("1m"),
            eq(Instant.parse("2026-01-14T14:30:00Z")), eq(DURING_RTH_WINTER.minusSeconds(1))))
            .thenReturn(List.of(candle(Instant.parse("2026-01-14T15:00:00Z"), 21000, 100)));

        VolumeProfileView view = service(DURING_RTH_WINTER).getProfile(Instrument.MNQ);

        assertThat(view.session().date()).isEqualTo("2026-01-14");
        assertThat(view.session().developing()).isTrue();
        assertThat(view.session().totalVolume()).isEqualTo(100);
    }

    @Test
    void preMarket_currentSessionIsPreviousCompletedDay() {
        // Tue 2026-04-14 full RTH window: 13:30Z .. 19:59:59Z (EDT)
        when(candleRepository.findCandlesBetween(eq(Instrument.MNQ), eq("1m"),
            eq(Instant.parse("2026-04-14T13:30:00Z")), eq(Instant.parse("2026-04-14T19:59:59Z"))))
            .thenReturn(List.of(candle(Instant.parse("2026-04-14T14:00:00Z"), 20950, 800)));

        VolumeProfileView view = service(PRE_MARKET).getProfile(Instrument.MNQ);

        assertThat(view.session().date()).isEqualTo("2026-04-14");
        assertThat(view.session().developing()).isFalse();
        assertThat(view.session().totalVolume()).isEqualTo(800);
    }

    @Test
    void priorSession_isMostRecentCompletedSessionWithData() {
        // Current = Wed 04-15 (developing); prior = Tue 04-14 (Monday is empty/holiday)
        when(candleRepository.findCandlesBetween(eq(Instrument.MNQ), eq("1m"),
            eq(Instant.parse("2026-04-14T13:30:00Z")), eq(Instant.parse("2026-04-14T19:59:59Z"))))
            .thenReturn(List.of(candle(Instant.parse("2026-04-14T14:00:00Z"), 20950, 800)));

        VolumeProfileView view = service(DURING_RTH).getProfile(Instrument.MNQ);

        assertThat(view.priorSession()).isNotNull();
        assertThat(view.priorSession().date()).isEqualTo("2026-04-14");
        assertThat(view.priorSession().developing()).isFalse();
    }

    @Test
    void overnightSession_completedAtRthTime_isExposed() {
        // Overnight for Wed 04-15: Tue 18:00 ET (22:00Z) -> Wed 09:30 ET (13:30Z).
        // At 15:00Z (during RTH) the overnight is completed.
        when(candleRepository.findCandlesBetween(eq(Instrument.MNQ), eq("1m"),
            eq(Instant.parse("2026-04-14T22:00:00Z")), eq(Instant.parse("2026-04-15T13:29:59Z"))))
            .thenReturn(List.of(candle(Instant.parse("2026-04-15T02:00:00Z"), 20900, 300)));

        VolumeProfileView view = service(DURING_RTH).getProfile(Instrument.MNQ);

        assertThat(view.overnight()).isNotNull();
        assertThat(view.overnight().date()).isEqualTo("2026-04-15");
        assertThat(view.overnight().developing()).isFalse();
        assertThat(view.overnight().totalVolume()).isEqualTo(300);
    }

    // ─── Naked POCs ────────────────────────────────────────────────────────────

    @Test
    void nakedPocs_priorPocUntouchedByLaterSessions_isListed() {
        // Mon 04-13 traded around 20800 (POC 20800), Tue 04-14 around 21000 — Monday's
        // POC was never revisited.
        when(candleRepository.findCandlesBetween(eq(Instrument.MNQ), eq("1m"),
            eq(Instant.parse("2026-04-13T13:30:00Z")), eq(Instant.parse("2026-04-13T19:59:59Z"))))
            .thenReturn(List.of(candle(Instant.parse("2026-04-13T14:00:00Z"), 20800.25, 600)));
        when(candleRepository.findCandlesBetween(eq(Instrument.MNQ), eq("1m"),
            eq(Instant.parse("2026-04-14T13:30:00Z")), eq(Instant.parse("2026-04-14T19:59:59Z"))))
            .thenReturn(List.of(candle(Instant.parse("2026-04-14T14:00:00Z"), 21000.25, 800)));
        // Developing Wednesday trades around 21050 — far from both prior POCs
        when(candleRepository.findCandlesBetween(eq(Instrument.MNQ), eq("1m"),
            eq(Instant.parse("2026-04-15T13:30:00Z")), eq(DURING_RTH.minusSeconds(1))))
            .thenReturn(List.of(candle(Instant.parse("2026-04-15T14:00:00Z"), 21050.25, 400)));

        VolumeProfileView view = service(DURING_RTH).getProfile(Instrument.MNQ);

        assertThat(view.nakedPocs())
            .extracting(VolumeProfileView.NakedPocView::date)
            .contains("2026-04-13", "2026-04-14");
    }

    @Test
    void nakedPocs_pocCoveredByDevelopingSessionRange_isExcluded() {
        // Tue POC 21000; Wednesday's developing range covers it -> not naked
        when(candleRepository.findCandlesBetween(eq(Instrument.MNQ), eq("1m"),
            eq(Instant.parse("2026-04-14T13:30:00Z")), eq(Instant.parse("2026-04-14T19:59:59Z"))))
            .thenReturn(List.of(candle(Instant.parse("2026-04-14T14:00:00Z"), 21000.25, 800)));
        when(candleRepository.findCandlesBetween(eq(Instrument.MNQ), eq("1m"),
            eq(Instant.parse("2026-04-15T13:30:00Z")), eq(DURING_RTH.minusSeconds(1))))
            .thenReturn(List.of(candle(Instant.parse("2026-04-15T14:00:00Z"), 21000.25, 400)));

        VolumeProfileView view = service(DURING_RTH).getProfile(Instrument.MNQ);

        assertThat(view.nakedPocs())
            .extracting(VolumeProfileView.NakedPocView::date)
            .doesNotContain("2026-04-14");
    }

    // ─── Cache ────────────────────────────────────────────────────────────────

    @Test
    void cache_secondCallWithinTtl_doesNotRecompute() {
        VolumeProfileService service = service(DURING_RTH);

        service.getProfile(Instrument.MNQ);
        verify(candleRepository, atLeastOnce()).findCandlesBetween(any(), any(), any(), any());
        int callsAfterFirst = org.mockito.Mockito.mockingDetails(candleRepository)
            .getInvocations().size();

        service.getProfile(Instrument.MNQ);

        assertThat(org.mockito.Mockito.mockingDetails(candleRepository).getInvocations())
            .hasSize(callsAfterFirst); // no additional repository reads
    }

    @Test
    void cache_isPerInstrument() {
        VolumeProfileService service = service(DURING_RTH);

        service.getProfile(Instrument.MNQ);
        service.getProfile(Instrument.MCL);

        // both instruments hit the repository for their current session window
        verify(candleRepository, times(1)).findCandlesBetween(eq(Instrument.MNQ), eq("1m"),
            eq(Instant.parse("2026-04-15T13:30:00Z")), eq(DURING_RTH.minusSeconds(1)));
        verify(candleRepository, times(1)).findCandlesBetween(eq(Instrument.MCL), eq("1m"),
            eq(Instant.parse("2026-04-15T13:30:00Z")), eq(DURING_RTH.minusSeconds(1)));
    }

    @Test
    void noData_returnsNullPocsAndEmptyNakedList() {
        VolumeProfileView view = service(DURING_RTH).getProfile(Instrument.MNQ);

        assertThat(view.session().poc()).isNull();
        assertThat(view.priorSession()).isNull();
        assertThat(view.overnight()).isNull();
        assertThat(view.nakedPocs()).isEmpty();
    }
}
