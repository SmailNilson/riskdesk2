package com.riskdesk.application.service;

import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.event.CvdDivergenceDetected;
import com.riskdesk.domain.orderflow.model.CvdDivergenceSignal;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import com.riskdesk.infrastructure.persistence.JpaCvdDivergencePaperTradeRepository;
import com.riskdesk.infrastructure.persistence.entity.CvdDivergencePaperTradeEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CvdDivergencePaperTradingServiceTest {

    /** Wednesday 2026-06-10 14:30 ET (EDT, UTC-4) — inside RTH. */
    private static final Instant IN_RTH = Instant.parse("2026-06-10T18:30:00Z");
    /** Wednesday 2026-06-10 18:00 ET — outside RTH (Globex evening). */
    private static final Instant OUT_RTH = Instant.parse("2026-06-10T22:00:00Z");
    /** Wednesday 2026-01-14 14:30 ET (EST, UTC-5) — inside RTH under winter time. */
    private static final Instant IN_RTH_WINTER = Instant.parse("2026-01-14T19:30:00Z");

    private JpaCvdDivergencePaperTradeRepository repository;
    private TickDataPort tickDataPort;
    private OrderFlowProperties properties;
    private CvdDivergencePaperTradingService service;

    @BeforeEach
    void setUp() {
        repository = mock(JpaCvdDivergencePaperTradeRepository.class);
        tickDataPort = mock(TickDataPort.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TickDataPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tickDataPort);
        properties = new OrderFlowProperties();
        service = new CvdDivergencePaperTradingService(repository, provider, properties);
    }

    @Test
    void opensShortOnBearishDivergenceInsideRth() {
        when(repository.findFirstByInstrumentAndStatusOrderByEntryTimeDesc(
            Instrument.MNQ, CvdDivergencePaperTradeEntity.STATUS_OPEN)).thenReturn(Optional.empty());

        service.onCvdDivergence(bearish(IN_RTH, 21500.0));

        ArgumentCaptor<CvdDivergencePaperTradeEntity> captor =
            ArgumentCaptor.forClass(CvdDivergencePaperTradeEntity.class);
        verify(repository).save(captor.capture());
        CvdDivergencePaperTradeEntity trade = captor.getValue();
        assertEquals("SHORT", trade.getDirection());
        assertEquals(21500.0, trade.getEntryPrice());
        assertEquals(IN_RTH, trade.getEntryTime());
        assertEquals(IN_RTH, trade.getLastSignalTime());
        assertEquals(CvdDivergencePaperTradeEntity.STATUS_OPEN, trade.getStatus());
        assertEquals(CvdDivergenceSignal.BEARISH, trade.getDivergenceType());
    }

    @Test
    void opensLongOnBullishDivergenceUnderWinterTime() {
        when(repository.findFirstByInstrumentAndStatusOrderByEntryTimeDesc(
            Instrument.MNQ, CvdDivergencePaperTradeEntity.STATUS_OPEN)).thenReturn(Optional.empty());

        service.onCvdDivergence(bullish(IN_RTH_WINTER, 21000.0));

        ArgumentCaptor<CvdDivergencePaperTradeEntity> captor =
            ArgumentCaptor.forClass(CvdDivergencePaperTradeEntity.class);
        verify(repository).save(captor.capture());
        assertEquals("LONG", captor.getValue().getDirection());
    }

    @Test
    void ignoresDivergenceOutsideRth() {
        when(repository.findFirstByInstrumentAndStatusOrderByEntryTimeDesc(
            Instrument.MNQ, CvdDivergencePaperTradeEntity.STATUS_OPEN)).thenReturn(Optional.empty());

        service.onCvdDivergence(bearish(OUT_RTH, 21500.0));

        verify(repository, never()).save(any());
    }

    @Test
    void sameDirectionEventRefreshesHoldWindow() {
        CvdDivergencePaperTradeEntity existing = openShort(IN_RTH, 21500.0);
        when(repository.findFirstByInstrumentAndStatusOrderByEntryTimeDesc(
            Instrument.MNQ, CvdDivergencePaperTradeEntity.STATUS_OPEN)).thenReturn(Optional.of(existing));

        Instant refresh = IN_RTH.plusSeconds(120);
        service.onCvdDivergence(bearish(refresh, 21490.0));

        assertEquals(refresh, existing.getLastSignalTime());
        assertEquals(CvdDivergencePaperTradeEntity.STATUS_OPEN, existing.getStatus());
        verify(repository, times(1)).save(existing);
    }

    @Test
    void oppositeDivergenceClosesAndFlips() {
        CvdDivergencePaperTradeEntity existing = openShort(IN_RTH, 21500.0);
        when(repository.findFirstByInstrumentAndStatusOrderByEntryTimeDesc(
            Instrument.MNQ, CvdDivergencePaperTradeEntity.STATUS_OPEN)).thenReturn(Optional.of(existing));

        Instant flipTime = IN_RTH.plusSeconds(300);
        service.onCvdDivergence(bullish(flipTime, 21490.0));

        assertEquals(CvdDivergencePaperTradeEntity.STATUS_CLOSED, existing.getStatus());
        assertEquals(CvdDivergencePaperTradeEntity.REASON_FLIPPED, existing.getCloseReason());
        assertEquals(21490.0, existing.getExitPrice());
        // SHORT 21500 -> 21490 = +10 points; MNQ $2/point.
        assertEquals(10.0, existing.getPnlPoints(), 1e-9);
        assertEquals(20.0, existing.getPnlCurrency(), 1e-9);

        ArgumentCaptor<CvdDivergencePaperTradeEntity> captor =
            ArgumentCaptor.forClass(CvdDivergencePaperTradeEntity.class);
        verify(repository, times(2)).save(captor.capture());
        CvdDivergencePaperTradeEntity flipped = captor.getAllValues().get(1);
        assertEquals("LONG", flipped.getDirection());
        assertEquals(21490.0, flipped.getEntryPrice());
        assertEquals(flipTime, flipped.getEntryTime());
    }

    @Test
    void schedulerClosesTradeAfterBadgeWindowExpires() {
        CvdDivergencePaperTradeEntity existing = openShort(IN_RTH, 21500.0);
        when(repository.findByStatus(CvdDivergencePaperTradeEntity.STATUS_OPEN))
            .thenReturn(List.of(existing));
        when(tickDataPort.currentAggregationReadOnly(Instrument.MNQ))
            .thenReturn(Optional.of(agg(21480.0)));
        // 601s past the last signal, still inside RTH (14:40 ET).
        service.setClock(Clock.fixed(IN_RTH.plusSeconds(601), ZoneOffset.UTC));

        service.closeExpiredTrades();

        assertEquals(CvdDivergencePaperTradeEntity.STATUS_CLOSED, existing.getStatus());
        assertEquals(CvdDivergencePaperTradeEntity.REASON_BADGE_EXPIRED, existing.getCloseReason());
        assertEquals(21480.0, existing.getExitPrice());
        assertEquals(20.0, existing.getPnlPoints(), 1e-9);
    }

    @Test
    void schedulerClosesAtSessionEndBeforeBadgeExpiry() {
        // Entry 15:58 ET; checked at 16:01 ET — only 180s elapsed (not expired) but RTH is over.
        Instant lateEntry = Instant.parse("2026-06-10T19:58:00Z");
        CvdDivergencePaperTradeEntity existing = openShort(lateEntry, 21500.0);
        when(repository.findByStatus(CvdDivergencePaperTradeEntity.STATUS_OPEN))
            .thenReturn(List.of(existing));
        when(tickDataPort.currentAggregationReadOnly(Instrument.MNQ))
            .thenReturn(Optional.of(agg(21510.0)));
        service.setClock(Clock.fixed(Instant.parse("2026-06-10T20:01:00Z"), ZoneOffset.UTC));

        service.closeExpiredTrades();

        assertEquals(CvdDivergencePaperTradeEntity.STATUS_CLOSED, existing.getStatus());
        assertEquals(CvdDivergencePaperTradeEntity.REASON_SESSION_END, existing.getCloseReason());
        // SHORT 21500 -> 21510 = -10 points.
        assertEquals(-10.0, existing.getPnlPoints(), 1e-9);
    }

    @Test
    void schedulerDefersCloseWhenNoLivePriceAvailable() {
        CvdDivergencePaperTradeEntity existing = openShort(IN_RTH, 21500.0);
        when(repository.findByStatus(CvdDivergencePaperTradeEntity.STATUS_OPEN))
            .thenReturn(List.of(existing));
        when(tickDataPort.currentAggregationReadOnly(Instrument.MNQ)).thenReturn(Optional.empty());
        service.setClock(Clock.fixed(IN_RTH.plusSeconds(900), ZoneOffset.UTC));

        service.closeExpiredTrades();

        assertEquals(CvdDivergencePaperTradeEntity.STATUS_OPEN, existing.getStatus());
        assertNull(existing.getExitPrice());
        verify(repository, never()).save(any());
    }

    @Test
    void disabledFlagStopsEventHandlingAndScheduler() {
        properties.getCvd().setPaperTradingEnabled(false);

        service.onCvdDivergence(bearish(IN_RTH, 21500.0));
        service.closeExpiredTrades();

        verifyNoInteractions(repository);
    }

    @Test
    void ignoresEventWithoutDetectionPrice() {
        service.onCvdDivergence(bearish(IN_RTH, Double.NaN));

        verifyNoInteractions(repository);
    }

    private static CvdDivergenceDetected bearish(Instant ts, double price) {
        return new CvdDivergenceDetected(Instrument.MNQ,
            new CvdDivergenceSignal(CvdDivergenceSignal.BEARISH, 21450.0, 21500.0, 5000, 4000,
                ts.minusSeconds(300)),
            price, ts);
    }

    private static CvdDivergenceDetected bullish(Instant ts, double price) {
        return new CvdDivergenceDetected(Instrument.MNQ,
            new CvdDivergenceSignal(CvdDivergenceSignal.BULLISH, 21050.0, 21000.0, -4000, -3000,
                ts.minusSeconds(300)),
            price, ts);
    }

    private static CvdDivergencePaperTradeEntity openShort(Instant entryTime, double entryPrice) {
        return new CvdDivergencePaperTradeEntity(Instrument.MNQ, "SHORT",
            CvdDivergenceSignal.BEARISH, entryTime, entryPrice,
            21450.0, 21500.0, 5000, 4000, entryTime.minusSeconds(300));
    }

    private static TickAggregation agg(double lastPrice) {
        return new TickAggregation(Instrument.MNQ, 0, 0, 0, 0, 0.0,
            TickAggregation.TREND_FLAT, false, null, IN_RTH, IN_RTH,
            TickAggregation.SOURCE_REAL_TICKS, lastPrice, lastPrice, lastPrice, lastPrice);
    }
}
