package com.riskdesk.infrastructure.marketdata;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Accumulates price ticks into OHLCV candles per instrument+timeframe.
 * When a period boundary is crossed, the completed candle is emitted via
 * the onCandleClosed callback.
 *
 * Extracted from the inner CandleAccumulator class in MarketDataService.
 */
public class CandleAccumulator {

    private final Map<String, BuildingCandle> building = new ConcurrentHashMap<>();

    public void accumulate(Instrument instrument, String timeframe, int periodSeconds,
                           BigDecimal price, long volume, Consumer<Candle> onCandleClosed) {
        String key = instrument.name() + ":" + timeframe;
        long now = Instant.now().getEpochSecond();
        long barStart = (now / periodSeconds) * periodSeconds;

        building.compute(key, (k, current) -> {
            if (current == null || current.barStart != barStart) {
                // New period: close old candle if exists, start new one
                if (current != null && onCandleClosed != null) {
                    onCandleClosed.accept(current.toCandle(instrument, timeframe));
                }
                return new BuildingCandle(barStart, price, price, price, price, volume);
            } else {
                current.update(price, volume);
                return current;
            }
        });
    }

    private static class BuildingCandle {
        final long barStart;
        BigDecimal open, high, low, close;
        long volume;

        BuildingCandle(long barStart, BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c, long vol) {
            this.barStart = barStart;
            open = o;
            high = h;
            low = l;
            close = c;
            volume = vol;
        }

        void update(BigDecimal price, long vol) {
            if (price.compareTo(high) > 0) high = price;
            if (price.compareTo(low) < 0) low = price;
            close = price;
            volume += vol;
        }

        Candle toCandle(Instrument inst, String tf) {
            return new Candle(inst, tf, Instant.ofEpochSecond(barStart), open, high, low, close, volume);
        }
    }
}
