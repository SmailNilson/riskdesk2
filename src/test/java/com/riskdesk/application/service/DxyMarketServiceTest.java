package com.riskdesk.application.service;

import com.riskdesk.application.dto.DxyHealthView;
import com.riskdesk.application.dto.DxySnapshotView;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.marketdata.model.DxySnapshot;
import com.riskdesk.domain.marketdata.model.FxPair;
import com.riskdesk.domain.marketdata.model.FxQuoteSnapshot;
import com.riskdesk.domain.marketdata.port.DxySnapshotRepositoryPort;
import com.riskdesk.domain.marketdata.port.FxQuoteProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DxyMarketServiceTest {

    @Test
    void refreshSyntheticDxy_persistsOnlyWhenSnapshotValuesChange() {
        FxQuoteProvider fxQuoteProvider = mock(FxQuoteProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<FxQuoteProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(fxQuoteProvider);

        DxySnapshotRepositoryPort repository = mock(DxySnapshotRepositoryPort.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        DxyPricePublisher pricePublisher = mock(DxyPricePublisher.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        DxyMarketService service = new DxyMarketService(provider, repository, candlePort, pricePublisher, eventPublisher, true);

        when(fxQuoteProvider.fetchQuotes()).thenReturn(completeQuotes(Instant.parse("2026-04-01T10:00:00Z")));
        when(repository.findLatestComplete()).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.refreshSyntheticDxy();
        service.refreshSyntheticDxy();

        verify(repository, times(1)).save(any(DxySnapshot.class));
        verify(pricePublisher, times(2)).publishIfChanged(any(DxySnapshot.class));
        assertEquals("UP", service.health().status());
        assertEquals("IBKR_SYNTHETIC", service.latestView().orElseThrow().source());
    }

    @Test
    void refreshSyntheticDxy_usesFallbackDbWhenLiveIsIncomplete() {
        @SuppressWarnings("unchecked")
        ObjectProvider<FxQuoteProvider> provider = mock(ObjectProvider.class);
        FxQuoteProvider fxQuoteProvider = mock(FxQuoteProvider.class);
        when(provider.getIfAvailable()).thenReturn(fxQuoteProvider);

        DxySnapshotRepositoryPort repository = mock(DxySnapshotRepositoryPort.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        DxyPricePublisher pricePublisher = mock(DxyPricePublisher.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        DxyMarketService service = new DxyMarketService(provider, repository, candlePort, pricePublisher, eventPublisher, true);

        Map<FxPair, FxQuoteSnapshot> incomplete = completeQuotes(Instant.parse("2026-04-01T10:00:00Z"));
        incomplete.remove(FxPair.USDCHF);
        when(fxQuoteProvider.fetchQuotes()).thenReturn(incomplete);

        DxySnapshot fallback = new DxySnapshot(
            Instant.parse("2026-04-01T09:59:00Z"),
            new BigDecimal("1.08110"),
            new BigDecimal("149.22000"),
            new BigDecimal("1.26220"),
            new BigDecimal("1.35120"),
            new BigDecimal("10.48050"),
            new BigDecimal("0.90215"),
            new BigDecimal("103.456789"),
            "IBKR_SYNTHETIC",
            true
        );
        when(repository.findLatestComplete()).thenReturn(Optional.of(fallback));

        service.refreshSyntheticDxy();

        DxySnapshotView latest = service.latestView().orElseThrow();
        DxyHealthView health = service.health();
        assertEquals("FALLBACK_DB", latest.source());
        assertEquals("DEGRADED", health.status());
        verify(repository, never()).save(any());
    }

    @Test
    void latestSnapshot_isUnavailableWhenClientPortalModeIsActive() {
        @SuppressWarnings("unchecked")
        ObjectProvider<FxQuoteProvider> provider = mock(ObjectProvider.class);
        DxySnapshotRepositoryPort repository = mock(DxySnapshotRepositoryPort.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        DxyPricePublisher pricePublisher = mock(DxyPricePublisher.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        DxyMarketService service = new DxyMarketService(provider, repository, candlePort, pricePublisher, eventPublisher, false);

        service.refreshSyntheticDxy();

        assertTrue(service.latestView().isEmpty());
        assertEquals("DOWN", service.health().status());
        assertFalse(service.supported());
        verifyNoInteractions(repository);
        verifyNoInteractions(pricePublisher);
    }

    @Test
    void computeComponentContributions_calculatesWeightedImpacts() {
        @SuppressWarnings("unchecked")
        ObjectProvider<FxQuoteProvider> provider = mock(ObjectProvider.class);
        DxySnapshotRepositoryPort repository = mock(DxySnapshotRepositoryPort.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        DxyPricePublisher pricePublisher = mock(DxyPricePublisher.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        DxyMarketService service = new DxyMarketService(provider, repository, candlePort, pricePublisher, eventPublisher, true);

        DxySnapshot current = new DxySnapshot(
            Instant.parse("2026-04-01T10:00:00Z"),
            new BigDecimal("1.07500"), // EURUSD down → USD_BULLISH
            new BigDecimal("150.00000"), // USDJPY up → USD_BULLISH
            new BigDecimal("1.26220"),
            new BigDecimal("1.35120"),
            new BigDecimal("10.48050"),
            new BigDecimal("0.90215"),
            new BigDecimal("104.500000"),
            "IBKR_SYNTHETIC", true
        );
        DxySnapshot baseline = new DxySnapshot(
            Instant.parse("2026-04-01T09:50:00Z"),
            new BigDecimal("1.08110"), // EURUSD was higher
            new BigDecimal("149.22000"), // USDJPY was lower
            new BigDecimal("1.26220"),
            new BigDecimal("1.35120"),
            new BigDecimal("10.48050"),
            new BigDecimal("0.90215"),
            new BigDecimal("103.800000"),
            "IBKR_SYNTHETIC", true
        );

        var contributions = service.computeComponentContributions(current, baseline);

        assertFalse(contributions.isEmpty());
        assertEquals(6, contributions.size());

        // EURUSD down → USD_BULLISH (biggest weight)
        var eurContrib = contributions.stream()
            .filter(c -> c.pair() == FxPair.EURUSD)
            .findFirst().orElseThrow();
        assertEquals("USD_BULLISH", eurContrib.impactDirection());
        assertTrue(eurContrib.pctChange().doubleValue() < 0); // EUR fell

        // USDJPY up → USD_BULLISH
        var jpyContrib = contributions.stream()
            .filter(c -> c.pair() == FxPair.USDJPY)
            .findFirst().orElseThrow();
        assertEquals("USD_BULLISH", jpyContrib.impactDirection());
        assertTrue(jpyContrib.pctChange().doubleValue() > 0); // JPY pair rose

        // Sorted by |weightedImpact| descending
        for (int i = 0; i < contributions.size() - 1; i++) {
            assertTrue(contributions.get(i).weightedImpact().abs()
                .compareTo(contributions.get(i + 1).weightedImpact().abs()) >= 0);
        }
    }

    private Map<FxPair, FxQuoteSnapshot> completeQuotes(Instant timestamp) {
        Map<FxPair, FxQuoteSnapshot> quotes = new EnumMap<>(FxPair.class);
        quotes.put(FxPair.EURUSD, quote(FxPair.EURUSD, "1.0810", "1.0812", "1.0815", timestamp));
        quotes.put(FxPair.USDJPY, quote(FxPair.USDJPY, "149.20", "149.24", "149.30", timestamp));
        quotes.put(FxPair.GBPUSD, quote(FxPair.GBPUSD, "1.2620", "1.2624", "1.2628", timestamp));
        quotes.put(FxPair.USDCAD, quote(FxPair.USDCAD, "1.3510", "1.3514", "1.3518", timestamp));
        quotes.put(FxPair.USDSEK, quote(FxPair.USDSEK, "10.4800", "10.4810", "10.4820", timestamp));
        quotes.put(FxPair.USDCHF, quote(FxPair.USDCHF, "0.9020", "0.9023", "0.9026", timestamp));
        return quotes;
    }

    private FxQuoteSnapshot quote(FxPair pair, String bid, String ask, String last, Instant timestamp) {
        return new FxQuoteSnapshot(
            pair,
            new BigDecimal(bid),
            new BigDecimal(ask),
            new BigDecimal(last),
            null,
            timestamp,
            "LIVE_PROVIDER"
        );
    }
}
