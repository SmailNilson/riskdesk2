package com.riskdesk.domain.engine.backtest;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds a continuous futures contract by splicing data from
 * multiple contract months (e.g., MAR26 + JUN26).
 *
 * Uses the front-month contract until a specified roll date,
 * then switches to the next contract. No back-adjustment is applied
 * (same as TradingView's default continuous contract MNQ1!).
 */
public class ContinuousContractBuilder {

    /**
     * Splice two contract month datasets into a continuous series.
     * Uses frontMonth data up to (and including) rollDate, then backMonth data after.
     *
     * @param frontMonth  candles from the expiring contract (e.g., MAR26)
     * @param backMonth   candles from the next contract (e.g., JUN26)
     * @param rollDate    timestamp after which to switch to backMonth
     * @param instrument  the instrument
     * @param timeframe   the timeframe
     */
    public static List<Candle> splice(
        List<Candle> frontMonth,
        List<Candle> backMonth,
        Instant rollDate,
        Instrument instrument,
        String timeframe
    ) {
        List<Candle> result = new ArrayList<>();

        // Add front-month candles up to roll date
        for (Candle c : frontMonth) {
            if (!c.getTimestamp().isAfter(rollDate)) {
                result.add(rebrand(c, instrument, timeframe));
            }
        }

        // Add back-month candles after roll date
        for (Candle c : backMonth) {
            if (c.getTimestamp().isAfter(rollDate)) {
                result.add(rebrand(c, instrument, timeframe));
            }
        }

        // Sort by timestamp
        result.sort(Comparator.comparing(Candle::getTimestamp));
        return result;
    }

    private static Candle rebrand(Candle c, Instrument instrument, String timeframe) {
        return new Candle(instrument, timeframe, c.getTimestamp(),
            c.getOpen(), c.getHigh(), c.getLow(), c.getClose(), c.getVolume());
    }
}
