package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.FootprintBar;
import com.riskdesk.domain.orderflow.port.FootprintPort;
import com.riskdesk.domain.orderflow.service.FootprintAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Infrastructure adapter that manages per-instrument {@link FootprintAggregator} instances.
 * Routes classified ticks to the correct aggregator and provides snapshots for the REST API.
 *
 * <p>Thread-safe: aggregators are created lazily and individual tick routing is synchronized
 * per aggregator instance.</p>
 */
@Component
public class IbkrFootprintAdapter implements FootprintPort {

    private static final Logger log = LoggerFactory.getLogger(IbkrFootprintAdapter.class);

    private final ConcurrentHashMap<Instrument, FootprintAggregator> aggregators = new ConcurrentHashMap<>();

    /**
     * Routes a classified trade tick to the correct footprint aggregator.
     * Creates the aggregator lazily if it does not yet exist.
     *
     * @param instrument     the instrument this tick belongs to
     * @param price          trade price
     * @param size           number of contracts
     * @param classification "BUY" or "SELL"
     * @param timestamp      tick timestamp
     */
    public void onTick(Instrument instrument, double price, long size,
                       String classification, Instant timestamp) {
        FootprintAggregator aggregator = aggregators.computeIfAbsent(instrument, k -> {
            double tickSize = k.getTickSize().doubleValue();
            log.info("Footprint: created aggregator for {} (tickSize={})", k, tickSize);
            return new FootprintAggregator(k, tickSize);
        });

        synchronized (aggregator) {
            aggregator.onTick(price, size, classification, timestamp);
        }
    }

    /**
     * Returns a snapshot of the current footprint bar for the given instrument and timeframe.
     * If no tick data has been accumulated, returns empty.
     *
     * @param instrument the instrument to query
     * @param timeframe  the bar timeframe label (e.g. "5m")
     * @return the current footprint bar snapshot, or empty if no data
     */
    @Override
    public Optional<FootprintBar> currentBar(Instrument instrument, String timeframe) {
        FootprintAggregator aggregator = aggregators.get(instrument);
        if (aggregator == null) {
            return Optional.empty();
        }

        synchronized (aggregator) {
            if (!aggregator.hasData()) {
                return Optional.empty();
            }
            long barTimestamp = Instant.now().getEpochSecond();
            return Optional.of(aggregator.snapshot(barTimestamp, timeframe));
        }
    }

    /**
     * Called when a candle closes: takes a final snapshot and resets the aggregator
     * for the next bar.
     *
     * @param instrument   the instrument whose candle closed
     * @param barTimestamp  epoch seconds of the closed candle's open
     * @param timeframe    the bar timeframe label (e.g. "5m")
     * @return the final footprint bar for the closed candle, or empty if no data
     */
    public Optional<FootprintBar> onCandleClose(Instrument instrument, long barTimestamp, String timeframe) {
        FootprintAggregator aggregator = aggregators.get(instrument);
        if (aggregator == null) {
            return Optional.empty();
        }

        synchronized (aggregator) {
            if (!aggregator.hasData()) {
                return Optional.empty();
            }
            FootprintBar bar = aggregator.snapshot(barTimestamp, timeframe);
            aggregator.reset();
            return Optional.of(bar);
        }
    }
}
