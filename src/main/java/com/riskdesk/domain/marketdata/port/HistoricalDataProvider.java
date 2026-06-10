package com.riskdesk.domain.marketdata.port;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * Port for fetching historical OHLCV candles from the internal market-data pipeline.
 * In this project the allowed source is IBKR only.
 */
public interface HistoricalDataProvider {
    List<Candle> fetchHistory(Instrument instrument, String timeframe, int count);
    boolean supports(Instrument instrument, String timeframe);

    /**
     * Fetches historical candles ending before the given time.
     * Used by the on-demand candle service to fill gaps when scrolling back.
     *
     * @param instrument the instrument to fetch
     * @param timeframe  bar size (e.g. "5m", "10m", "1h")
     * @param endTime    fetch bars ending at or before this instant
     * @param count      approximate number of bars to request
     * @return candles ordered oldest-first; empty list if unsupported or unavailable
     */
    default List<Candle> fetchHistoryBefore(Instrument instrument, String timeframe, Instant endTime, int count) {
        return List.of();
    }

    /**
     * Fetches every available bar within the closed range {@code [from, to]}, oldest-first.
     *
     * <p>Unlike {@link #fetchHistory} (count-targeted, anchored at "now"), this walks
     * backward from {@code to} down to {@code from}, crossing expired contract boundaries
     * as needed, so an arbitrary historical window can be reconstructed for deep backfill.
     * Implementations must respect IBKR pacing limits. The result may contain gaps where
     * the exchange was closed; it is the caller's job to persist idempotently.</p>
     *
     * @return candles ordered oldest-first; empty list if unsupported or unavailable
     */
    default List<Candle> fetchHistoryRange(Instrument instrument, String timeframe, Instant from, Instant to) {
        return List.of();
    }

    /**
     * Streaming variant of {@link #fetchHistoryRange(Instrument, String, Instant, Instant)} that
     * hands each fetched chunk to {@code chunkSink} the moment it is retrieved, instead of
     * accumulating the whole {@code [from, to]} window in memory. Heap stays bounded to a single
     * chunk (~one IBKR request) — essential for deep 1m windows that span months and would
     * otherwise hold ~10^5 candles in RAM before a single write.
     *
     * <p>Bars within a chunk are oldest-first, but chunks may arrive newest-first (the walk goes
     * backward) and may overlap across contract boundaries, so the sink must persist idempotently.</p>
     *
     * @return total number of candles handed to the sink
     */
    default int fetchHistoryRange(Instrument instrument, String timeframe, Instant from, Instant to,
                                  Consumer<List<Candle>> chunkSink) {
        List<Candle> all = fetchHistoryRange(instrument, timeframe, from, to);
        if (!all.isEmpty()) {
            chunkSink.accept(all);
        }
        return all.size();
    }

    /**
     * Streaming range fetch on the provider's <em>continuous</em> contract series instead of the
     * current front-month + expired-contract walk. The continuous series returns, at every past
     * date, the contract that was actually front at that date (TradingView-style stitching), so a
     * deep window is never reconstructed from today's front month projected into the past — that
     * projection yields thin back-month bars with curve offset for dates where the contract was
     * not yet front.
     *
     * <p>Same streaming/idempotence contract as
     * {@link #fetchHistoryRange(Instrument, String, Instant, Instant, Consumer)}. Continuous data
     * is historical-only; it cannot back live subscriptions or orders.</p>
     *
     * @return total number of candles handed to the sink; 0 when unsupported
     */
    default int fetchContinuousHistoryRange(Instrument instrument, String timeframe, Instant from, Instant to,
                                            Consumer<List<Candle>> chunkSink) {
        return 0;
    }

    /**
     * Streaming range fetch pinned to one <em>explicit</em> (possibly expired) contract month
     * instead of the front-month walk or the continuous series. The operator names the contract
     * that was front over {@code [from, to]} (e.g. {@code "202603"} for a window inside that
     * contract's front period), and every bar comes from — and is tagged with — that single
     * contract. This is the fallback when the gateway build rejects CONTFUT contracts
     * (IBKR error 200) but still serves expired single contracts.
     *
     * <p>Same streaming/idempotence contract as
     * {@link #fetchHistoryRange(Instrument, String, Instant, Instant, Consumer)}.</p>
     *
     * @param contractMonth target contract month, {@code YYYYMM}
     * @return total number of candles handed to the sink; 0 when unsupported or unresolvable
     */
    default int fetchContractMonthHistoryRange(Instrument instrument, String timeframe, String contractMonth,
                                               Instant from, Instant to, Consumer<List<Candle>> chunkSink) {
        return 0;
    }
}
