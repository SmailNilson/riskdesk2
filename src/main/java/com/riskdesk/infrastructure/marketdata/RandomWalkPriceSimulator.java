package com.riskdesk.infrastructure.marketdata;

import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates simulated prices using a random walk from a last known price.
 * Extracted from MarketDataService.simulateTick() and defaultPrice() methods.
 */
public class RandomWalkPriceSimulator {

    private final Map<Instrument, BigDecimal> lastPrices = new ConcurrentHashMap<>();

    private static final Map<Instrument, BigDecimal> DEFAULT_PRICES = Map.of(
        Instrument.MCL, new BigDecimal("62.40"),
        Instrument.MGC, new BigDecimal("2038.50"),
        Instrument.E6, new BigDecimal("1.08200"),
        Instrument.MNQ, new BigDecimal("18250.00")
    );

    public BigDecimal simulate(Instrument instrument) {
        BigDecimal last = lastPrices.getOrDefault(instrument, DEFAULT_PRICES.get(instrument));
        double move = (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.001 * last.doubleValue();
        BigDecimal next = last.add(BigDecimal.valueOf(move))
            .setScale(instrument.getTickSize().scale(), RoundingMode.HALF_UP);
        if (next.compareTo(BigDecimal.ZERO) <= 0) next = instrument.getTickSize();
        lastPrices.put(instrument, next);
        return next;
    }

    public void setLastPrice(Instrument instrument, BigDecimal price) {
        lastPrices.put(instrument, price);
    }
}
