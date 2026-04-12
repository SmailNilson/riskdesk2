package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.marketdata.port.HistoricalDataProvider;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * On-demand candle service for TradingView UDF-style chart loading.
 *
 * <p>The chart requests bars via {@code getBars(instrument, timeframe, toEpoch, countBack)}.
 * The service first checks PostgreSQL; if not enough candles are available, it fetches
 * the missing range from IBKR and persists new bars for future requests.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li><b>Request coalescing</b>: concurrent identical requests share a single IBKR fetch</li>
 *   <li><b>60s cooldown</b>: prevents repeated IBKR calls for the same range</li>
 *   <li><b>Duplicate safety</b>: catches DataIntegrityViolationException on save</li>
 * </ul>
 */
@Service
public class OnDemandCandleService {

    private static final Logger log = LoggerFactory.getLogger(OnDemandCandleService.class);
    private static final long COOLDOWN_MS = 60_000L;

    private final CandleRepositoryPort candlePort;
    private final HistoricalDataProvider historicalProvider;
    private final ActiveContractRegistry contractRegistry;

    /** In-flight IBKR fetches keyed by instrument:timeframe:bucketedFrom:bucketedTo */
    private final ConcurrentHashMap<String, CompletableFuture<List<Candle>>> inflight = new ConcurrentHashMap<>();

    /** Cooldown tracker: key -> last fetch epoch millis */
    private final ConcurrentHashMap<String, Instant> cooldowns = new ConcurrentHashMap<>();

    public OnDemandCandleService(CandleRepositoryPort candlePort,
                                 ObjectProvider<HistoricalDataProvider> historicalProviderProvider,
                                 ActiveContractRegistry contractRegistry) {
        this.candlePort = candlePort;
        this.historicalProvider = historicalProviderProvider.getIfAvailable();
        this.contractRegistry = contractRegistry;
    }

    /**
     * Response record for the v2 candle endpoint.
     *
     * @param bars     candles ordered oldest-first
     * @param noData   true if no more historical data is available (stop scrolling)
     * @param nextTime epoch seconds of the oldest bar, or null if noData
     */
    public record OnDemandCandleResponse(List<Candle> bars, boolean noData, Long nextTime) {}

    /**
     * Main entry point. Returns up to {@code countBack} candles ending at {@code toEpoch}.
     *
     * @param instrument the instrument
     * @param timeframe  bar size (e.g. "5m", "10m", "1h")
     * @param toEpoch    end timestamp in epoch seconds (null = now)
     * @param countBack  number of bars requested
     * @return response with bars, noData flag, and nextTime for pagination
     */
    public OnDemandCandleResponse getBars(Instrument instrument, String timeframe, Long toEpoch, int countBack) {
        Instant to = toEpoch != null ? Instant.ofEpochSecond(toEpoch) : Instant.now();
        long tfSeconds = timeframeSeconds(timeframe);
        // 1.5x buffer to account for weekends and holidays
        Instant from = to.minusSeconds((long) (countBack * tfSeconds * 1.5));

        // Step 1: Query DB
        List<Candle> dbCandles = candlePort.findCandlesBetween(instrument, timeframe, from, to);

        // Step 2: If DB has enough, return immediately (no IBKR call)
        if (dbCandles.size() >= countBack) {
            log.debug("OnDemand {}/{}: DB has {} candles (requested {}), returning from DB only",
                instrument, timeframe, dbCandles.size(), countBack);
            return buildResponse(dbCandles, countBack);
        }

        // Step 3: Not enough data — attempt IBKR fetch if provider is available
        if (historicalProvider == null || !historicalProvider.supports(instrument, timeframe)) {
            log.debug("OnDemand {}/{}: no historical provider available, returning {} DB candles",
                instrument, timeframe, dbCandles.size());
            return buildResponse(dbCandles, countBack);
        }

        // Step 4: Check cooldown
        String cooldownKey = buildCooldownKey(instrument, timeframe, from, to);
        Instant lastFetch = cooldowns.get(cooldownKey);
        if (lastFetch != null && Instant.now().toEpochMilli() - lastFetch.toEpochMilli() < COOLDOWN_MS) {
            log.debug("OnDemand {}/{}: in cooldown, returning {} DB candles", instrument, timeframe, dbCandles.size());
            return buildResponse(dbCandles, countBack);
        }

        // Step 5: Request coalescing — share in-flight fetch
        String inflightKey = instrument + ":" + timeframe + ":" + from.getEpochSecond() + ":" + to.getEpochSecond();
        try {
            CompletableFuture<List<Candle>> future = inflight.computeIfAbsent(inflightKey,
                key -> CompletableFuture.supplyAsync(() -> fetchAndPersist(instrument, timeframe, to, countBack)));

            future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("OnDemand {}/{}: IBKR fetch failed — {}", instrument, timeframe, e.getMessage());
        } finally {
            inflight.remove(inflightKey);
        }

        // Step 6: Update cooldown
        cooldowns.put(cooldownKey, Instant.now());

        // Step 7: Re-query DB with the (potentially) new data
        List<Candle> finalCandles = candlePort.findCandlesBetween(instrument, timeframe, from, to);
        return buildResponse(finalCandles, countBack);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private List<Candle> fetchAndPersist(Instrument instrument, String timeframe, Instant endTime, int count) {
        log.info("OnDemand {}/{}: fetching {} bars from IBKR (endTime={})", instrument, timeframe, count, endTime);
        List<Candle> fetched = historicalProvider.fetchHistoryBefore(instrument, timeframe, endTime, count);

        if (fetched.isEmpty()) {
            return List.of();
        }

        // Tag with contract month
        String contractMonth = contractRegistry.getContractMonth(instrument).orElse(null);
        if (contractMonth != null) {
            for (Candle c : fetched) {
                if (c.getContractMonth() == null) {
                    c.setContractMonth(contractMonth);
                }
            }
        }

        // Persist — catch duplicate key violations gracefully
        try {
            candlePort.saveAll(fetched);
            log.info("OnDemand {}/{}: persisted {} new candles", instrument, timeframe, fetched.size());
        } catch (DataIntegrityViolationException e) {
            log.debug("OnDemand {}/{}: some candles already exist (expected for overlapping ranges)", instrument, timeframe);
            // Save individually to skip duplicates
            int saved = 0;
            for (Candle c : fetched) {
                try {
                    candlePort.save(c);
                    saved++;
                } catch (DataIntegrityViolationException ignored) {
                    // duplicate — skip
                }
            }
            log.info("OnDemand {}/{}: persisted {} new candles (skipped duplicates)", instrument, timeframe, saved);
        }

        return fetched;
    }

    private OnDemandCandleResponse buildResponse(List<Candle> candles, int countBack) {
        if (candles.isEmpty()) {
            return new OnDemandCandleResponse(List.of(), true, null);
        }

        // Sort oldest-first (should already be, but ensure)
        List<Candle> sorted = candles.stream()
            .sorted(Comparator.comparing(Candle::getTimestamp))
            .toList();

        // Trim to countBack if we have more
        if (sorted.size() > countBack) {
            sorted = sorted.subList(sorted.size() - countBack, sorted.size());
        }

        Long nextTime = sorted.get(0).getTimestamp().getEpochSecond();
        return new OnDemandCandleResponse(sorted, false, nextTime);
    }

    private String buildCooldownKey(Instrument instrument, String timeframe, Instant from, Instant to) {
        // Bucket to 5-minute granularity to avoid key explosion
        long fromBucket = from.getEpochSecond() / 300;
        long toBucket = to.getEpochSecond() / 300;
        return instrument + ":" + timeframe + ":" + fromBucket + ":" + toBucket;
    }

    private static long timeframeSeconds(String timeframe) {
        return switch (timeframe) {
            case "5m"  -> 300L;
            case "10m" -> 600L;
            case "30m" -> 1800L;
            case "1h"  -> 3600L;
            case "4h"  -> 14400L;
            case "1d"  -> 86400L;
            default    -> 600L;
        };
    }
}
