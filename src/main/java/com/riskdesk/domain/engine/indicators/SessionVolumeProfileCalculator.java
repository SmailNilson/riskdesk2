package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Session volume profile from 1m candles: POC, VAH, VAL (70% value area) and the
 * naked-POC ladder. Pure domain logic — no Spring, no JPA.
 *
 * <p><b>Binning</b> — unlike {@link VolumeProfileCalculator} (which drops each candle's
 * whole volume at its typical price), this calculator distributes each candle's volume
 * across its high–low range proportionally to the share of that range each price bucket
 * covers (uniform-across-ticks, the standard approximation when per-tick data is not
 * replayed). A candle fully inside one bucket contributes all of its volume there.</p>
 *
 * <p><b>Value area</b> — same outward expansion as the legacy calculator: start at the
 * POC bucket and repeatedly absorb the larger of the next bucket above vs below until
 * ≥ 70% of the session volume is covered (ties expand upward).</p>
 */
public class SessionVolumeProfileCalculator {

    private static final double VALUE_AREA_FRACTION = 0.70;
    /** Safety cap on buckets a single candle may span — guards against corrupt OHLC rows. */
    private static final long MAX_BUCKETS_PER_CANDLE = 100_000;

    /**
     * Computes the session profile.
     *
     * @param candles    the session's 1m candles (order irrelevant)
     * @param bucketSize price bucket size in points (e.g. 1.0 for MNQ, 0.05 for MCL)
     * @return the profile, or {@code null} when there is no usable volume
     */
    public SessionProfile compute(List<Candle> candles, BigDecimal bucketSize) {
        if (candles == null || candles.isEmpty()
                || bucketSize == null || bucketSize.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        double bucket = bucketSize.doubleValue();
        // bucket index -> distributed volume (fractional after range distribution)
        TreeMap<Long, Double> histogram = new TreeMap<>();
        long totalVolume = 0;
        BigDecimal rangeHigh = null;
        BigDecimal rangeLow = null;

        for (Candle candle : candles) {
            if (candle == null || candle.getHigh() == null || candle.getLow() == null) continue;
            rangeHigh = rangeHigh == null || candle.getHigh().compareTo(rangeHigh) > 0 ? candle.getHigh() : rangeHigh;
            rangeLow = rangeLow == null || candle.getLow().compareTo(rangeLow) < 0 ? candle.getLow() : rangeLow;
            if (candle.getVolume() <= 0) continue;

            double low = candle.getLow().doubleValue();
            double high = candle.getHigh().doubleValue();
            long volume = candle.getVolume();
            totalVolume += volume;

            long lowIdx = bucketIndex(low, bucket);
            long highIdx = bucketIndex(high, bucket);
            if (highIdx - lowIdx > MAX_BUCKETS_PER_CANDLE) continue; // corrupt row — skip distribution

            if (highIdx == lowIdx || high <= low) {
                histogram.merge(lowIdx, (double) volume, Double::sum);
                continue;
            }
            double span = high - low;
            for (long idx = lowIdx; idx <= highIdx; idx++) {
                double overlapStart = Math.max(low, idx * bucket);
                double overlapEnd = Math.min(high, (idx + 1) * bucket);
                double weight = Math.max(0, overlapEnd - overlapStart) / span;
                if (weight > 0) {
                    histogram.merge(idx, volume * weight, Double::sum);
                }
            }
        }

        if (histogram.isEmpty() || totalVolume == 0) {
            return null;
        }

        // POC: bucket with the highest distributed volume (first max, ascending scan)
        long pocIdx = histogram.firstKey();
        double pocVolume = -1;
        for (Map.Entry<Long, Double> entry : histogram.entrySet()) {
            if (entry.getValue() > pocVolume) {
                pocVolume = entry.getValue();
                pocIdx = entry.getKey();
            }
        }

        // 70% value area: expand outward from POC, absorbing the larger neighbour
        List<Long> indices = new ArrayList<>(histogram.keySet());
        int pocPos = indices.indexOf(pocIdx);
        int lowPos = pocPos;
        int highPos = pocPos;
        double covered = histogram.get(pocIdx);
        double target = VALUE_AREA_FRACTION * sum(histogram);

        while (covered < target && (lowPos > 0 || highPos < indices.size() - 1)) {
            double aboveVol = highPos < indices.size() - 1 ? histogram.get(indices.get(highPos + 1)) : -1;
            double belowVol = lowPos > 0 ? histogram.get(indices.get(lowPos - 1)) : -1;
            if (aboveVol >= belowVol) {
                highPos++;
                covered += aboveVol;
            } else {
                lowPos--;
                covered += belowVol;
            }
        }

        return new SessionProfile(
            bucketPrice(pocIdx, bucketSize),
            bucketPrice(indices.get(highPos), bucketSize),
            bucketPrice(indices.get(lowPos), bucketSize),
            totalVolume,
            rangeHigh,
            rangeLow
        );
    }

    /**
     * Naked-POC ladder. A session's POC is "naked" (a.k.a. virgin POC) while no
     * <em>later</em> session's high–low range has touched it.
     *
     * @param sessionsOldestFirst completed sessions, oldest first — each is both a
     *                            naked-POC candidate and a toucher for older sessions
     * @param developingRange     the current (in-progress) session's range so far, used
     *                            only as a toucher — its own POC is still developing;
     *                            may be {@code null}
     * @return naked POCs, oldest first
     */
    public List<NakedPoc> nakedPocs(List<SessionPocRange> sessionsOldestFirst, PriceRange developingRange) {
        List<NakedPoc> naked = new ArrayList<>();
        if (sessionsOldestFirst == null) return naked;
        for (int i = 0; i < sessionsOldestFirst.size(); i++) {
            SessionPocRange candidate = sessionsOldestFirst.get(i);
            if (candidate == null || candidate.poc() == null) continue;
            boolean touched = false;
            for (int j = i + 1; j < sessionsOldestFirst.size() && !touched; j++) {
                touched = touches(sessionsOldestFirst.get(j), candidate.poc());
            }
            if (!touched && developingRange != null) {
                touched = developingRange.contains(candidate.poc());
            }
            if (!touched) {
                naked.add(new NakedPoc(candidate.poc(), candidate.date()));
            }
        }
        return naked;
    }

    private static boolean touches(SessionPocRange session, BigDecimal price) {
        if (session == null || session.rangeLow() == null || session.rangeHigh() == null) return false;
        return new PriceRange(session.rangeLow(), session.rangeHigh()).contains(price);
    }

    private static long bucketIndex(double price, double bucketSize) {
        // epsilon guards FP representation at exact bucket boundaries (see FootprintAggregator)
        return (long) Math.floor(price / bucketSize + 1e-9);
    }

    private static BigDecimal bucketPrice(long index, BigDecimal bucketSize) {
        return bucketSize.multiply(BigDecimal.valueOf(index)).stripTrailingZeros();
    }

    private static double sum(TreeMap<Long, Double> histogram) {
        double total = 0;
        for (double v : histogram.values()) total += v;
        return total;
    }

    /** Computed profile for one session. Prices are bucket lower bounds. */
    public record SessionProfile(
        BigDecimal poc,
        BigDecimal vah,
        BigDecimal val,
        long totalVolume,
        BigDecimal rangeHigh,
        BigDecimal rangeLow
    ) {}

    /** A completed session's POC plus its full high–low range, for naked-POC scans. */
    public record SessionPocRange(LocalDate date, BigDecimal poc, BigDecimal rangeLow, BigDecimal rangeHigh) {}

    /** Inclusive price range. */
    public record PriceRange(BigDecimal low, BigDecimal high) {
        public boolean contains(BigDecimal price) {
            if (price == null || low == null || high == null) return false;
            return price.compareTo(low) >= 0 && price.compareTo(high) <= 0;
        }
    }

    /** A still-untouched session POC. */
    public record NakedPoc(BigDecimal price, LocalDate date) {}

}
