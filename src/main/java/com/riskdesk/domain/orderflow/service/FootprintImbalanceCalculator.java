package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.orderflow.model.FootprintLevel;
import com.riskdesk.domain.orderflow.model.ImbalanceZone;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;

/**
 * Pure domain logic for footprint imbalance analysis (industry-standard diagonal
 * convention). Shared by the live {@link FootprintAggregator} and by history
 * reconstruction from persisted profile JSON.
 *
 * <p><b>Diagonal imbalance</b> — for buckets sorted ascending with step = bucket size:
 * <ul>
 *   <li>buy imbalance at P: {@code buyVolume(P) ≥ ratio × sellVolume(P − step)} —
 *       buyers lifting offers at P versus sellers hitting bids one level lower;</li>
 *   <li>sell imbalance at P: {@code sellVolume(P) ≥ ratio × buyVolume(P + step)}.</li>
 * </ul>
 * A missing neighbour bucket counts as zero volume (the ratio is then trivially met),
 * so the <b>minimum-volume filter</b> carries the edge cases: the larger cell of the
 * diagonal pair must be at least {@code minCellVolume} contracts — a 4-vs-1 pair must
 * not flag.</p>
 *
 * <p><b>Stacked zones</b> — runs of at least {@value #MIN_STACKED_BUCKETS} consecutive
 * (bucket-adjacent) levels flagged on the same side.</p>
 *
 * <p><b>Unfinished auction</b> — the bar's extreme bucket traded on both sides
 * (buyVolume &gt; 0 and sellVolume &gt; 0): the auction did not finish at the extreme.</p>
 */
public final class FootprintImbalanceCalculator {

    /** Minimum consecutive flagged buckets to form a stacked zone. */
    public static final int MIN_STACKED_BUCKETS = 3;

    private FootprintImbalanceCalculator() {}

    /**
     * Builds enriched levels (ascending by price) from raw per-bucket volumes.
     *
     * @param volumes       bucket lower bound → {buyVolume, sellVolume}, sorted ascending
     * @param bucketSize    price bucket size (adjacency step)
     * @param ratio         diagonal dominance ratio (e.g. 3.0 = 300%)
     * @param minCellVolume minimum contracts on the larger cell of the diagonal pair
     */
    public static List<FootprintLevel> computeLevels(NavigableMap<Double, long[]> volumes,
                                                     double bucketSize, double ratio, long minCellVolume) {
        List<FootprintLevel> levels = new ArrayList<>(volumes.size());
        for (var entry : volumes.entrySet()) {
            double price = entry.getKey();
            long buy = entry.getValue()[0];
            long sell = entry.getValue()[1];

            long sellBelow = volumeAt(volumes, price - bucketSize, bucketSize, 1);
            long buyAbove = volumeAt(volumes, price + bucketSize, bucketSize, 0);

            boolean diagonalBuy = isDiagonalImbalance(buy, sellBelow, ratio, minCellVolume);
            boolean diagonalSell = isDiagonalImbalance(sell, buyAbove, ratio, minCellVolume);

            levels.add(new FootprintLevel(price, buy, sell, buy - sell,
                isSamePriceImbalance(buy, sell, ratio), diagonalBuy, diagonalSell));
        }
        return levels;
    }

    /**
     * Stacked-imbalance zones for one side: runs of ≥ {@value #MIN_STACKED_BUCKETS}
     * bucket-adjacent levels whose diagonal flag is set on that side.
     *
     * @param levelsAscending levels sorted ascending by price
     * @param bucketSize      adjacency step — levels more than ~1 bucket apart break the run
     * @param buySide         true → diagonal buy flags, false → diagonal sell flags
     */
    public static List<ImbalanceZone> stackedZones(List<FootprintLevel> levelsAscending,
                                                   double bucketSize, boolean buySide) {
        List<ImbalanceZone> zones = new ArrayList<>();
        double runFrom = 0;
        double runTo = 0;
        int runCount = 0;
        Double previousFlagged = null;

        for (FootprintLevel level : levelsAscending) {
            boolean flagged = buySide ? level.diagonalBuyImbalance() : level.diagonalSellImbalance();
            if (flagged) {
                boolean adjacent = previousFlagged != null
                    && Math.abs(level.price() - previousFlagged - bucketSize) < bucketSize * 0.01;
                if (adjacent) {
                    runTo = level.price();
                    runCount++;
                } else {
                    closeRun(zones, runFrom, runTo, runCount);
                    runFrom = level.price();
                    runTo = level.price();
                    runCount = 1;
                }
                previousFlagged = level.price();
            } else {
                closeRun(zones, runFrom, runTo, runCount);
                runCount = 0;
                previousFlagged = null;
            }
        }
        closeRun(zones, runFrom, runTo, runCount);
        return zones;
    }

    /** True when the bar's highest bucket traded on both sides — unfinished auction up. */
    public static boolean unfinishedHigh(List<FootprintLevel> levelsAscending) {
        if (levelsAscending.isEmpty()) return false;
        FootprintLevel top = levelsAscending.get(levelsAscending.size() - 1);
        return top.buyVolume() > 0 && top.sellVolume() > 0;
    }

    /** True when the bar's lowest bucket traded on both sides — unfinished auction down. */
    public static boolean unfinishedLow(List<FootprintLevel> levelsAscending) {
        if (levelsAscending.isEmpty()) return false;
        FootprintLevel bottom = levelsAscending.get(0);
        return bottom.buyVolume() > 0 && bottom.sellVolume() > 0;
    }

    // -------------------------------------------------------------------------

    private static boolean isDiagonalImbalance(long aggressor, long diagonalOpposite,
                                               double ratio, long minCellVolume) {
        if (aggressor <= 0) return false;
        if (Math.max(aggressor, diagonalOpposite) < minCellVolume) return false;
        return aggressor >= ratio * diagonalOpposite;
    }

    /**
     * @deprecated legacy same-price 3:1 flag, kept only to fill
     * {@link FootprintLevel#imbalance()} for persisted-JSON backward compatibility.
     */
    @Deprecated
    private static boolean isSamePriceImbalance(long buyVolume, long sellVolume, double ratio) {
        if (buyVolume == 0 && sellVolume == 0) return false;
        if (sellVolume == 0) return buyVolume >= ratio;
        if (buyVolume == 0) return sellVolume >= ratio;
        return buyVolume >= ratio * sellVolume || sellVolume >= ratio * buyVolume;
    }

    /**
     * Volume of the bucket whose key is (within FP tolerance) {@code price}; 0 when the
     * bucket does not exist. Tolerance is a fraction of the bucket size — keys are
     * normalized to 6 decimals by the aggregator, so drift is far below it.
     */
    private static long volumeAt(NavigableMap<Double, long[]> volumes, double price,
                                 double bucketSize, int side) {
        double tolerance = bucketSize * 0.01;
        var entry = volumes.ceilingEntry(price - tolerance);
        if (entry != null && Math.abs(entry.getKey() - price) <= tolerance) {
            return entry.getValue()[side];
        }
        return 0;
    }

    private static void closeRun(List<ImbalanceZone> zones, double from, double to, int count) {
        if (count >= MIN_STACKED_BUCKETS) {
            zones.add(new ImbalanceZone(from, to, count));
        }
    }
}
