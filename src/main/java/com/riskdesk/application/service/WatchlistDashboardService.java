package com.riskdesk.application.service;

import com.riskdesk.application.dto.IndicatorSeriesSnapshot;
import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.analysis.port.WatchlistCandleRepositoryPort;
import com.riskdesk.domain.marketdata.port.WatchlistInstrumentMarketDataPort;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.IbkrWatchlist;
import com.riskdesk.domain.model.IbkrWatchlistInstrument;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.WatchlistCandle;
import com.riskdesk.domain.trading.port.IbkrWatchlistRepositoryPort;
import com.riskdesk.presentation.dto.LivePriceView;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WatchlistDashboardService {

    private static final int DEFAULT_LIMIT = 500;
    private static final long REFRESH_COOLDOWN_MS = 15_000L;
    private static final String CONID_PREFIX = "CONID:";
    private static final String LOCAL_PREFIX = "LOCAL:";
    private static final String SYMBOL_PREFIX = "SYMBOL:";
    private static final String CODE_PREFIX = "CODE:";

    private final IbkrWatchlistRepositoryPort watchlistRepositoryPort;
    private final WatchlistCandleRepositoryPort watchlistCandleRepositoryPort;
    private final WatchlistInstrumentMarketDataPort watchlistInstrumentMarketDataPort;
    private final IndicatorService indicatorService;
    private final Map<String, Long> refreshTimestamps = new ConcurrentHashMap<>();

    public WatchlistDashboardService(IbkrWatchlistRepositoryPort watchlistRepositoryPort,
                                     WatchlistCandleRepositoryPort watchlistCandleRepositoryPort,
                                     WatchlistInstrumentMarketDataPort watchlistInstrumentMarketDataPort,
                                     IndicatorService indicatorService) {
        this.watchlistRepositoryPort = watchlistRepositoryPort;
        this.watchlistCandleRepositoryPort = watchlistCandleRepositoryPort;
        this.watchlistInstrumentMarketDataPort = watchlistInstrumentMarketDataPort;
        this.indicatorService = indicatorService;
    }

    public List<WatchlistCandle> recentCandles(String instrumentSelector, String timeframe, int limit) {
        String selectorKey = normalizeSelector(instrumentSelector);
        List<WatchlistCandle> stored = loadStoredCandles(selectorKey, timeframe, limit);
        if (shouldRefresh(selectorKey, timeframe, stored, limit)) {
            refreshCandles(instrumentSelector, selectorKey, timeframe, Math.max(limit, DEFAULT_LIMIT));
            stored = loadStoredCandles(selectorKey, timeframe, limit);
        }
        Collections.reverse(stored);
        return stored;
    }

    public IndicatorSnapshot computeSnapshot(String instrumentSelector, String timeframe) {
        String selectorKey = normalizeSelector(instrumentSelector);
        List<WatchlistCandle> candles = recentCandles(selectorKey, timeframe, DEFAULT_LIMIT);
        return indicatorService.computeSnapshot(selectorKey, timeframe, toSyntheticCandles(candles));
    }

    public IndicatorSeriesSnapshot computeSeries(String instrumentSelector, String timeframe, int limit) {
        String selectorKey = normalizeSelector(instrumentSelector);
        List<WatchlistCandle> candles = recentCandles(selectorKey, timeframe, limit);
        return indicatorService.computeSeries(selectorKey, timeframe, toSyntheticCandles(candles));
    }

    public Optional<LivePriceView> currentPrice(String instrumentSelector) {
        String selectorKey = normalizeSelector(instrumentSelector);
        return resolveInstrument(instrumentSelector)
            .flatMap(watchlistInstrumentMarketDataPort::fetchLatestPrice)
            .map(price -> new LivePriceView(selectorKey, price, Instant.now(), "IB_GATEWAY_SNAPSHOT"));
    }

    private List<WatchlistCandle> loadStoredCandles(String instrumentCode, String timeframe, int limit) {
        return new ArrayList<>(watchlistCandleRepositoryPort.findRecentCandles(normalize(instrumentCode), timeframe, limit));
    }

    private boolean shouldRefresh(String instrumentCode, String timeframe, List<WatchlistCandle> stored, int limit) {
        if (stored.isEmpty()) {
            return allowRefresh(instrumentCode, timeframe);
        }

        WatchlistCandle latest = stored.get(0);
        Instant freshnessCutoff = Instant.now().minusSeconds(timeframeSeconds(timeframe) * 2L);
        boolean stale = latest.getTimestamp() == null || latest.getTimestamp().isBefore(freshnessCutoff);
        boolean insufficientDepth = stored.size() < Math.min(limit, 120);
        return (stale || insufficientDepth) && allowRefresh(instrumentCode, timeframe);
    }

    private boolean allowRefresh(String instrumentCode, String timeframe) {
        String key = normalize(instrumentCode) + ":" + timeframe;
        long now = System.currentTimeMillis();
        Long lastRefresh = refreshTimestamps.get(key);
        if (lastRefresh != null && now - lastRefresh < REFRESH_COOLDOWN_MS) {
            return false;
        }
        refreshTimestamps.put(key, now);
        return true;
    }

    private void refreshCandles(String instrumentSelector, String selectorKey, String timeframe, int limit) {
        resolveInstrument(instrumentSelector).ifPresent(instrument -> {
            instrument.setInstrumentCode(selectorKey);
            List<WatchlistCandle> candles = watchlistInstrumentMarketDataPort.fetchHistory(instrument, timeframe, limit);
            if (candles.isEmpty()) {
                return;
            }
            watchlistCandleRepositoryPort.deleteByInstrumentCodeAndTimeframe(selectorKey, timeframe);
            watchlistCandleRepositoryPort.saveAll(candles);
        });
    }

    private Optional<IbkrWatchlistInstrument> resolveInstrument(String instrumentSelector) {
        String normalized = normalizeSelector(instrumentSelector);
        return watchlistRepositoryPort.findAll().stream()
            .map(IbkrWatchlist::getInstruments)
            .flatMap(List::stream)
            .filter(instrument -> matchesInstrument(instrument, normalized))
            .filter(instrument -> instrument.getConid() != null || hasText(instrument.getSymbol()) || hasText(instrument.getLocalSymbol()))
            .findFirst();
    }

    private boolean matchesInstrument(IbkrWatchlistInstrument instrument, String target) {
        if (target == null) {
            return false;
        }
        if (target.startsWith(CONID_PREFIX)) {
            return instrument.getConid() != null && target.equals(CONID_PREFIX + instrument.getConid());
        }
        if (target.startsWith(LOCAL_PREFIX)) {
            return hasText(instrument.getLocalSymbol())
                && target.equals(LOCAL_PREFIX + normalize(instrument.getLocalSymbol()));
        }
        if (target.startsWith(SYMBOL_PREFIX)) {
            return hasText(instrument.getSymbol())
                && target.equals(SYMBOL_PREFIX + normalize(instrument.getSymbol()));
        }
        if (target.startsWith(CODE_PREFIX)) {
            return hasText(instrument.getInstrumentCode())
                && target.equals(CODE_PREFIX + normalize(instrument.getInstrumentCode()));
        }
        return target.equals(normalize(instrument.getInstrumentCode()))
            || target.equals(normalize(instrument.getSymbol()))
            || target.equals(firstToken(instrument.getLocalSymbol()));
    }

    private List<Candle> toSyntheticCandles(List<WatchlistCandle> candles) {
        return candles.stream()
            .sorted(Comparator.comparing(WatchlistCandle::getTimestamp))
            .map(candle -> new Candle(
                Instrument.MCL,
                candle.getTimeframe(),
                candle.getTimestamp(),
                candle.getOpen(),
                candle.getHigh(),
                candle.getLow(),
                candle.getClose(),
                candle.getVolume()
            ))
            .toList();
    }

    private String firstToken(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        String[] tokens = normalized.split("\\s+");
        return tokens.length == 0 ? normalized : tokens[0];
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private String normalizeSelector(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        int separatorIndex = trimmed.indexOf(':');
        if (separatorIndex <= 0) {
            return normalize(trimmed);
        }

        String prefix = trimmed.substring(0, separatorIndex).trim().toUpperCase(Locale.ROOT);
        String body = trimmed.substring(separatorIndex + 1).trim();
        if (body.isEmpty()) {
            return null;
        }

        return switch (prefix) {
            case "CONID" -> CONID_PREFIX + body;
            case "LOCAL" -> LOCAL_PREFIX + body.toUpperCase(Locale.ROOT);
            case "SYMBOL" -> SYMBOL_PREFIX + body.toUpperCase(Locale.ROOT);
            case "CODE" -> CODE_PREFIX + body.toUpperCase(Locale.ROOT);
            default -> normalize(trimmed);
        };
    }

    private long timeframeSeconds(String timeframe) {
        return switch (timeframe) {
            case "5m" -> 5L * 60L;
            case "10m" -> 10L * 60L;
            case "1h" -> 60L * 60L;
            case "4h" -> 4L * 60L * 60L;
            case "1d" -> 24L * 60L * 60L;
            default -> 60L;
        };
    }
}
