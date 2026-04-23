package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.DepthMetrics;
import com.riskdesk.domain.orderflow.model.WallEvent;
import com.riskdesk.domain.orderflow.port.MarketDepthPort;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Infrastructure adapter implementing MarketDepthPort.
 * Maintains a ConcurrentHashMap of per-instrument MutableOrderBook instances,
 * updated from IbGatewayNativeClient's depth callbacks.
 */
@Component
public class IbkrMarketDepthAdapter implements MarketDepthPort {

    private static final double DEFAULT_WALL_THRESHOLD_MULTIPLIER = 5.0;

    private final ConcurrentHashMap<Instrument, MutableOrderBook> books = new ConcurrentHashMap<>();
    private final double wallThresholdMultiplier;

    public IbkrMarketDepthAdapter() {
        this.wallThresholdMultiplier = DEFAULT_WALL_THRESHOLD_MULTIPLIER;
    }

    @Override
    public Optional<DepthMetrics> currentDepth(Instrument instrument) {
        MutableOrderBook book = books.get(instrument);
        if (book == null || !book.hasData()) {
            return Optional.empty();
        }
        return Optional.of(book.computeMetrics(instrument));
    }

    @Override
    public List<WallEvent> recentWallEvents(Instrument instrument, Duration lookback) {
        MutableOrderBook book = books.get(instrument);
        if (book == null) {
            return Collections.emptyList();
        }
        return book.recentWallEvents(lookback);
    }

    @Override
    public boolean isDepthAvailable(Instrument instrument) {
        MutableOrderBook book = books.get(instrument);
        return book != null && book.hasData();
    }

    /**
     * Called by IbGatewayNativeClient's depth handler to route IBKR depth updates
     * to the correct MutableOrderBook.
     *
     * @param instrument the instrument this depth update belongs to
     * @param position   level index (0-based)
     * @param operation  "INSERT", "UPDATE", or "DELETE"
     * @param side       "BUY" (bid) or "SELL" (ask)
     * @param price      price at this level
     * @param size       size at this level
     */
    public void onDepthUpdate(Instrument instrument, int position, String operation,
                              String side, double price, long size) {
        MutableOrderBook book = books.computeIfAbsent(instrument,
            k -> new MutableOrderBook(wallThresholdMultiplier));
        book.updateDepth(position, operation, side, price, size, instrument);
    }

    public double getWallThresholdMultiplier() {
        return wallThresholdMultiplier;
    }
}
