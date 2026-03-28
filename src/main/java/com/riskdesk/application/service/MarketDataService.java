package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.marketdata.event.CandleClosed;
import com.riskdesk.domain.marketdata.event.MarketPriceUpdated;
import com.riskdesk.domain.marketdata.port.MarketDataProvider;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Polls the configured MarketDataProvider every N seconds.
 * – Updates open position P&L via PositionService.
 * – Pushes price ticks to WebSocket /topic/prices.
 * – Accumulates 10m and 1h OHLCV candles and persists them via CandleRepositoryPort.
 * – Publishes MarketPriceUpdated and CandleClosed domain events.
 * – Falls back to the latest stored IBKR-backed candle close from the database when IBKR is unavailable.
 */
@Service
@Profile("!test")
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private static final Map<String, Long> TIMEFRAMES = Map.of(
        "10m", 10L,
        "1h",  60L
    );
    private static final String[] FALLBACK_TIMEFRAMES = {"5m", "10m", "1h", "4h", "1d"};
    private static final long FRESH_CACHE_SECONDS = 15L;
    private static final long INSTANT_FETCH_TIMEOUT_MS = 1200L;

    private final MarketDataProvider        marketDataProvider;
    private final PositionService           positionService;
    private final AlertService              alertService;
    private final CandleRepositoryPort      candlePort;
    private final ActiveContractRegistry    contractRegistry;
    private final SimpMessagingTemplate     messagingTemplate;
    private final ApplicationEventPublisher eventPublisher;

    private final Map<Instrument, BigDecimal>    lastPrice    = new ConcurrentHashMap<>();
    private final Map<Instrument, Instant>       lastTimestamp = new ConcurrentHashMap<>();
    private final Map<Instrument, String>        lastSource   = new ConcurrentHashMap<>();
    private final Map<String, CandleAccumulator> accumulators = new ConcurrentHashMap<>();
    private volatile boolean databaseFallbackActive = false;

    public MarketDataService(MarketDataProvider marketDataProvider,
                             PositionService positionService,
                             AlertService alertService,
                             CandleRepositoryPort candlePort,
                             ActiveContractRegistry contractRegistry,
                             SimpMessagingTemplate messagingTemplate,
                             ApplicationEventPublisher eventPublisher) {
        this.marketDataProvider = marketDataProvider;
        this.positionService    = positionService;
        this.alertService       = alertService;
        this.candlePort         = candlePort;
        this.contractRegistry   = contractRegistry;
        this.messagingTemplate  = messagingTemplate;
        this.eventPublisher     = eventPublisher;
    }

    @Scheduled(fixedDelayString = "${riskdesk.market-data.poll-interval:5000}")
    public void pollPrices() {
        Map<Instrument, BigDecimal> prices = marketDataProvider.fetchPrices();
        Instant now = Instant.now();
        boolean usedDatabaseFallback = false;

        for (Instrument instrument : Instrument.values()) {
            BigDecimal price = prices.get(instrument);
            Instant timestamp = now;
            boolean fallbackPrice = false;

            if (price == null) {
                StoredPrice storedPrice = loadStoredPrice(instrument);
                if (storedPrice == null) continue;
                price = storedPrice.price();
                timestamp = storedPrice.timestamp();
                fallbackPrice = true;
                usedDatabaseFallback = true;
            }

            if (fallbackPrice && samePrice(lastPrice.get(instrument), price)) {
                continue;
            }

            lastPrice.put(instrument, price);
            lastTimestamp.put(instrument, timestamp);
            lastSource.put(instrument, fallbackPrice ? "FALLBACK_DB" : "LIVE_PROVIDER");

            positionService.updateMarketPrice(instrument, price);
            sendPriceUpdate(instrument, price, timestamp);
            eventPublisher.publishEvent(new MarketPriceUpdated(instrument.name(), price, timestamp));

            if (!fallbackPrice) {
                for (String tf : TIMEFRAMES.keySet()) {
                    accumulate(instrument, tf, price, now);
                }

                alertService.evaluate(instrument);
            }
        }

        if (usedDatabaseFallback && !databaseFallbackActive) {
            log.warn("IBKR unavailable: serving last known prices from the database.");
            databaseFallbackActive = true;
        } else if (!usedDatabaseFallback && databaseFallbackActive) {
            log.info("IBKR recovered: live market data polling resumed.");
            databaseFallbackActive = false;
        }
    }

    // -------------------------------------------------------------------------
    // WebSocket push
    // -------------------------------------------------------------------------

    private void sendPriceUpdate(Instrument instrument, BigDecimal price, Instant now) {
        try {
            Map<String, Object> payload = Map.of(
                "instrument",  instrument.name(),
                "displayName", instrument.getDisplayName(),
                "price",       price,
                "timestamp",   now.toString()
            );
            messagingTemplate.convertAndSend("/topic/prices", payload);
        } catch (Exception e) {
            log.debug("WebSocket send failed for {}: {}", instrument, e.getMessage());
        }
    }

    private StoredPrice loadStoredPrice(Instrument instrument) {
        Candle latest = null;

        for (String timeframe : FALLBACK_TIMEFRAMES) {
            java.util.List<Candle> candles = candlePort.findRecentCandles(instrument, timeframe, 1);
            if (candles.isEmpty()) continue;

            Candle candidate = candles.get(0);
            if (latest == null || candidate.getTimestamp().isAfter(latest.getTimestamp())) {
                latest = candidate;
            }
        }

        if (latest == null) {
            return null;
        }

        return new StoredPrice(latest.getClose(), latest.getTimestamp(), "FALLBACK_DB");
    }

    public StoredPrice latestPrice(Instrument instrument) {
        BigDecimal live = lastPrice.get(instrument);
        Instant liveTimestamp = lastTimestamp.get(instrument);
        String source = lastSource.get(instrument);
        if (live != null && liveTimestamp != null) {
            return new StoredPrice(live, liveTimestamp, source != null ? source : "CACHE");
        }
        StoredPrice fallback = loadStoredPrice(instrument);
        if (fallback == null) {
            return null;
        }
        return new StoredPrice(fallback.price(), fallback.timestamp(), "FALLBACK_DB");
    }

    public StoredPrice currentPrice(Instrument instrument) {
        StoredPrice cached = latestPrice(instrument);
        if (cached != null
            && "CACHE".equals(cached.source())
            && cached.timestamp() != null
            && cached.timestamp().isAfter(Instant.now().minusSeconds(FRESH_CACHE_SECONDS))) {
            return cached;
        }

        try {
            BigDecimal instant = CompletableFuture
                .supplyAsync(() -> marketDataProvider.fetchPrice(instrument).orElse(null))
                .get(INSTANT_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (instant != null) {
                Instant now = Instant.now();
                lastPrice.put(instrument, instant);
                lastTimestamp.put(instrument, now);
                return new StoredPrice(instant, now, "LIVE_PROVIDER");
            }
        } catch (Exception e) {
            log.debug("Instant price fetch failed for {}: {}", instrument, e.getMessage());
        }
        return cached;
    }

    private boolean samePrice(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    // -------------------------------------------------------------------------
    // Candle accumulation
    // -------------------------------------------------------------------------

    private void accumulate(Instrument instrument, String timeframe, BigDecimal price, Instant now) {
        String  contractMonth = contractRegistry.getContractMonth(instrument).orElse(null);
        String  key           = instrument.name() + ":" + timeframe;
        long    periodMins    = TIMEFRAMES.get(timeframe);
        Instant periodStart   = truncateToPeriod(now, periodMins);

        CandleAccumulator acc = accumulators.get(key);

        if (acc == null) {
            accumulators.put(key, new CandleAccumulator(instrument, timeframe, contractMonth, periodStart, price));
            return;
        }

        if (acc.periodStart.isBefore(periodStart)) {
            Candle closed = acc.build();
            candlePort.save(closed);
            log.debug("Saved candle {} {} {} O={} H={} L={} C={}",
                instrument, timeframe, contractMonth,
                closed.getOpen(), closed.getHigh(), closed.getLow(), closed.getClose());
            eventPublisher.publishEvent(new CandleClosed(instrument.name(), timeframe, periodStart));
            accumulators.put(key, new CandleAccumulator(instrument, timeframe, contractMonth, periodStart, price));
        } else {
            acc.update(price);
        }
    }

    private static Instant truncateToPeriod(Instant ts, long periodMinutes) {
        long epochMin    = ts.getEpochSecond() / 60;
        long periodStart = (epochMin / periodMinutes) * periodMinutes;
        return Instant.ofEpochSecond(periodStart * 60);
    }

    // -------------------------------------------------------------------------
    // Inner accumulator
    // -------------------------------------------------------------------------

    private static class CandleAccumulator {
        final Instrument instrument;
        final String     timeframe;
        final String     contractMonth;
        final Instant    periodStart;
        final BigDecimal open;
        BigDecimal high;
        BigDecimal low;
        BigDecimal close;
        long volume = 1;

        CandleAccumulator(Instrument instrument, String timeframe, String contractMonth,
                          Instant periodStart, BigDecimal firstPrice) {
            this.instrument    = instrument;
            this.timeframe     = timeframe;
            this.contractMonth = contractMonth;
            this.periodStart   = periodStart;
            this.open  = firstPrice;
            this.high  = firstPrice;
            this.low   = firstPrice;
            this.close = firstPrice;
        }

        void update(BigDecimal price) {
            if (price.compareTo(high) > 0) high  = price;
            if (price.compareTo(low)  < 0) low   = price;
            close = price;
            volume++;
        }

        Candle build() {
            return new Candle(instrument, timeframe, contractMonth, periodStart, open, high, low, close, volume);
        }
    }

    public record StoredPrice(BigDecimal price, Instant timestamp, String source) {}
}
