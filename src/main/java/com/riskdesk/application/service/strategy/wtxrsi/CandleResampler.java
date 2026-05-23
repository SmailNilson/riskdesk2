package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory OHLCV resampling. The repository persists 1m candles; higher
 * timeframes (5m, 10m, 15m, 1h, 4h) are derived by aggregating consecutive
 * 1m bars into bucketed bars whose timestamp is the bucket's <i>start</i>.
 *
 * <p>Why not SQL? The {@code candles} table is already partitioned by
 * (instrument, timeframe, timestamp); querying 1m candles for a multi-day
 * backtest pulls a tractable volume (< 200 k rows) and aggregating in JVM
 * keeps the cost of a one-off backtest off the DB. The existing
 * {@code CandleAccumulatorService} uses the same convention.
 */
public final class CandleResampler {

    private CandleResampler() {}

    public static List<Candle> resample(List<Candle> oneMinute, String targetTimeframe) {
        Duration bucket = parse(targetTimeframe);
        long bucketSecs = bucket.toSeconds();
        if (bucketSecs <= 0) throw new IllegalArgumentException("invalid timeframe: " + targetTimeframe);
        if (oneMinute.isEmpty()) return List.of();
        if ("1m".equalsIgnoreCase(targetTimeframe) || "1min".equalsIgnoreCase(targetTimeframe)) {
            return oneMinute;
        }

        Instrument instrument = oneMinute.get(0).getInstrument();
        List<Candle> out = new ArrayList<>();
        Instant bucketStart = null;
        BigDecimal o = null, h = null, l = null, c = null;
        long volSum = 0;

        for (Candle src : oneMinute) {
            long t = src.getTimestamp().getEpochSecond();
            long start = (t / bucketSecs) * bucketSecs;
            Instant startInstant = Instant.ofEpochSecond(start);
            if (bucketStart == null) {
                bucketStart = startInstant;
                o = src.getOpen();
                h = src.getHigh();
                l = src.getLow();
                c = src.getClose();
                volSum = src.getVolume();
                continue;
            }
            if (!startInstant.equals(bucketStart)) {
                out.add(new Candle(instrument, targetTimeframe, bucketStart, o, h, l, c, volSum));
                bucketStart = startInstant;
                o = src.getOpen();
                h = src.getHigh();
                l = src.getLow();
                c = src.getClose();
                volSum = src.getVolume();
            } else {
                if (src.getHigh().compareTo(h) > 0) h = src.getHigh();
                if (src.getLow().compareTo(l) < 0) l = src.getLow();
                c = src.getClose();
                volSum += src.getVolume();
            }
        }
        if (bucketStart != null) {
            out.add(new Candle(instrument, targetTimeframe, bucketStart, o, h, l, c, volSum));
        }
        return out;
    }

    private static Duration parse(String tf) {
        String t = tf.toLowerCase().trim();
        if (t.endsWith("m")) return Duration.ofMinutes(Long.parseLong(t.substring(0, t.length() - 1)));
        if (t.endsWith("h")) return Duration.ofHours(Long.parseLong(t.substring(0, t.length() - 1)));
        if (t.endsWith("d")) return Duration.ofDays(Long.parseLong(t.substring(0, t.length() - 1)));
        throw new IllegalArgumentException("unsupported timeframe: " + tf);
    }
}
