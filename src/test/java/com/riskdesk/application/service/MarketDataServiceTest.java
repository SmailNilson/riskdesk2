package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.marketdata.port.MarketDataProvider;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.*;

class MarketDataServiceTest {

    @Test
    void fallsBackToLatestStoredDatabasePriceWhenProviderReturnsEmpty() {
        MarketDataProvider marketDataProvider = () -> Map.of();
        PositionService positionService = mock(PositionService.class);
        AlertService alertService = mock(AlertService.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        Candle older10m = candle(Instrument.MCL, "10m", "2026-03-23T10:00:00Z", "101.25");
        Candle newer5m = candle(Instrument.MCL, "5m", "2026-03-23T10:05:00Z", "101.75");

        when(candlePort.findRecentCandles(any(), anyString(), eq(1))).thenReturn(List.of());
        when(candlePort.findRecentCandles(Instrument.MCL, "10m", 1)).thenReturn(List.of(older10m));
        when(candlePort.findRecentCandles(Instrument.MCL, "5m", 1)).thenReturn(List.of(newer5m));

        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);

        MarketDataService service = new MarketDataService(
            marketDataProvider,
            positionService,
            alertService,
            candlePort,
            contractRegistry,
            messagingTemplate,
            eventPublisher
        );

        service.pollPrices();

        verify(positionService).updateMarketPrice(Instrument.MCL, new BigDecimal("101.75"));
        verify(alertService, never()).evaluate(any());
        verify(candlePort, never()).save(any());
        verify(messagingTemplate).convertAndSend(eq("/topic/prices"), (Object) argThat(payload -> {
            if (!(payload instanceof Map<?, ?> map)) return false;
            return "MCL".equals(map.get("instrument"))
                && new BigDecimal("101.75").compareTo(new BigDecimal(String.valueOf(map.get("price")))) == 0
                && "2026-03-23T10:05:00Z".equals(String.valueOf(map.get("timestamp")));
        }));
    }

    @Test
    void accumulate_rollsOverOnlyMatchingInstrumentAndTimeframeBucket() {
        MarketDataProvider marketDataProvider = mock(MarketDataProvider.class);
        PositionService positionService = mock(PositionService.class);
        AlertService alertService = mock(AlertService.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);
        when(contractRegistry.getContractMonth(Instrument.MCL)).thenReturn(Optional.empty());

        MarketDataService service = new MarketDataService(
            marketDataProvider,
            positionService,
            alertService,
            candlePort,
            contractRegistry,
            messagingTemplate,
            eventPublisher
        );

        Instant periodOne = Instant.parse("2026-03-30T10:04:00Z");
        Instant periodTwo = Instant.parse("2026-03-30T10:15:00Z");

        ReflectionTestUtils.invokeMethod(service, "accumulate",
                Instrument.MCL, "10m", new BigDecimal("62.40"), periodOne);
        ReflectionTestUtils.invokeMethod(service, "accumulate",
                Instrument.MCL, "1h", new BigDecimal("62.50"), periodOne);
        ReflectionTestUtils.invokeMethod(service, "accumulate",
                Instrument.MCL, "10m", new BigDecimal("62.80"), periodOne);
        ReflectionTestUtils.invokeMethod(service, "accumulate",
                Instrument.MCL, "10m", new BigDecimal("63.10"), periodTwo);

        verify(candlePort, times(1)).save(argThat(candle ->
                candle.getInstrument() == Instrument.MCL
                        && "10m".equals(candle.getTimeframe())
                        && candle.getOpen().compareTo(new BigDecimal("62.40")) == 0
                        && candle.getHigh().compareTo(new BigDecimal("62.80")) == 0
                        && candle.getClose().compareTo(new BigDecimal("62.80")) == 0));
        verify(eventPublisher, times(1)).publishEvent((Object) argThat(event ->
                event instanceof com.riskdesk.domain.marketdata.event.CandleClosed closed
                        && "MCL".equals(closed.instrument())
                        && "10m".equals(closed.timeframe())));
    }

    private static Candle candle(Instrument instrument, String timeframe, String timestamp, String close) {
        BigDecimal price = new BigDecimal(close);
        return new Candle(
            instrument,
            timeframe,
            Instant.parse(timestamp),
            price,
            price,
            price,
            price,
            1L
        );
    }
}
