package com.riskdesk.domain.shared;

import com.riskdesk.domain.model.Candle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Normalizes a raw OHLCV candle series into a mathematically clean "Market Time"
 * series suitable for indicator computation and chart display.
 *
 * <p>Two operations are provided and should be applied in order:
 * <ol>
 *   <li><b>purgeHorsSession</b> — drops candles that fall inside the CME daily
 *       maintenance window (17:00–18:00 ET Mon–Thu) or weekend closure (Fri 17:00 ET →
 *       Sun 18:00 ET).  Keeping these candles artificially flattens moving averages
 *       and biases oscillators toward the Friday close.</li>
 *   <li><b>forwardFill</b> — inserts synthetic flat candles (OHLC = previous close,
 *       volume = 0) for any intra-session gap, so every period slot is represented.
 *       Without this, a 20-period EMA computed over sparse data represents an
 *       indeterminate wall-clock span rather than the expected 100 minutes (5m chart).</li>
 * </ol>
 *
 * <p>Forward-fill only runs for intraday timeframes (≤ 4h) and only fills slots that
 * are fully within an active trading session.  A gap that crosses a maintenance window
 * or session boundary is left as-is, so no synthetic candles are ever generated for
 * closed-market periods.  A per-gap cap of {@value #MAX_FILL_GAP} candles prevents
 * unbounded allocation for anomalous data.
 */
public final class CandleSeriesNormalizer {

    /** Safety cap: do not fill more than this many missing slots per gap. */
    private static final int MAX_FILL_GAP = 100;

    private CandleSeriesNormalizer() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns a new list containing only candles whose timestamps are inside an
     * active CME trading session (i.e. {@link TradingSessionResolver#isMaintenanceWindow}
     * returns {@code false}).
     *
     * <p>The input list is not modified.
     */
    public static List<Candle> purgeHorsSession(List<Candle> candles) {
        List<Candle> result = new ArrayList<>(candles.size());
        for (Candle c : candles) {
            if (!TradingSessionResolver.isMaintenanceWindow(c.getTimestamp(), c.getInstrument())) {
                result.add(c);
            }
        }
        return result;
    }

    /**
     * Fills intra-session gaps in a sorted (oldest-first) candle list.
     *
     * <p>For each consecutive pair of candles, if there are missing period slots
     * between them and every intermediate slot is within an active trading session,
     * synthetic flat candles are inserted: OHLC = previous close, volume = 0.
     *
     * <p>No candles are inserted when:
     * <ul>
     *   <li>the timeframe is daily or weekly ({@code "1d"}, {@code "1w"})</li>
     *   <li>an intermediate slot falls inside a maintenance window or weekend</li>
     *   <li>the gap exceeds {@value #MAX_FILL_GAP} missing slots</li>
     * </ul>
     *
     * @param candles   sorted oldest-first; must already have hors-session candles removed
     * @param timeframe e.g. {@code "5m"}, {@code "10m"}, {@code "1h"}, {@code "4h"}
     * @return new list with gaps filled; same list reference if no fill was needed
     */
    public static List<Candle> forwardFill(List<Candle> candles, String timeframe) {
        long periodSeconds = timeframeToSeconds(timeframe);
        if (periodSeconds <= 0 || candles.size() < 2) {
            return candles;
        }

        List<Candle> result = new ArrayList<>(candles.size() + 32);
        for (int i = 0; i < candles.size(); i++) {
            Candle current = candles.get(i);
            result.add(current);
            if (i < candles.size() - 1) {
                fillGap(current, candles.get(i + 1).getTimestamp(), periodSeconds, result);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private static void fillGap(Candle prev, Instant nextTimestamp,
                                 long periodSeconds, List<Candle> out) {
        Instant fillTime = prev.getTimestamp().plusSeconds(periodSeconds);
        int inserted = 0;
        while (fillTime.isBefore(nextTimestamp)) {
            if (TradingSessionResolver.isMaintenanceWindow(fillTime, prev.getInstrument())) {
                // Gap crosses a session boundary — leave it as a real gap
                break;
            }
            if (++inserted > MAX_FILL_GAP) {
                break; // safety cap against anomalous IBKR data
            }
            out.add(new Candle(
                    prev.getInstrument(),
                    prev.getTimeframe(),
                    prev.getContractMonth(),
                    fillTime,
                    prev.getClose(), prev.getClose(), prev.getClose(), prev.getClose(),
                    0L
            ));
            fillTime = fillTime.plusSeconds(periodSeconds);
        }
    }

    /** Maps a timeframe string to its period in seconds; returns -1 for daily/weekly. */
    static long timeframeToSeconds(String timeframe) {
        return switch (timeframe) {
            case "1m"  -> 60L;
            case "5m"  -> 300L;
            case "10m" -> 600L;
            case "30m" -> 1_800L;
            case "1h"  -> 3_600L;
            case "4h"  -> 14_400L;
            default    -> -1L; // "1d", "1w", etc. — no fill
        };
    }
}
