package com.riskdesk.infrastructure.marketdata;

import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RandomWalkPriceSimulatorTest {

    @Test
    void simulate_returnsPositivePrice() {
        RandomWalkPriceSimulator simulator = new RandomWalkPriceSimulator();

        for (Instrument instrument : Instrument.values()) {
            BigDecimal price = simulator.simulate(instrument);
            assertTrue(price.compareTo(BigDecimal.ZERO) > 0,
                "Price for " + instrument + " should be positive, got " + price);
        }
    }

    @Test
    void simulate_returnsPriceNearDefault_within1Percent() {
        RandomWalkPriceSimulator simulator = new RandomWalkPriceSimulator();

        // MCL default is 62.40, so result should be within 1% (61.78 - 63.02)
        BigDecimal price = simulator.simulate(Instrument.MCL);
        BigDecimal defaultPrice = new BigDecimal("62.40");
        BigDecimal lowerBound = defaultPrice.multiply(new BigDecimal("0.99"));
        BigDecimal upperBound = defaultPrice.multiply(new BigDecimal("1.01"));

        assertTrue(price.compareTo(lowerBound) >= 0 && price.compareTo(upperBound) <= 0,
            "Price " + price + " should be within 1% of default " + defaultPrice);
    }

    @Test
    void multipleSimulations_produceDifferentPrices() {
        RandomWalkPriceSimulator simulator = new RandomWalkPriceSimulator();
        Set<BigDecimal> prices = new HashSet<>();

        // Run 50 simulations; at least some should differ due to randomness
        for (int i = 0; i < 50; i++) {
            prices.add(simulator.simulate(Instrument.MCL));
        }

        assertTrue(prices.size() > 1,
            "50 simulations should produce at least 2 distinct prices, got " + prices.size());
    }

    @Test
    void setLastPrice_affectsNextSimulation() {
        RandomWalkPriceSimulator simulator = new RandomWalkPriceSimulator();

        // Set a very different last price
        simulator.setLastPrice(Instrument.MCL, new BigDecimal("1000.00"));
        BigDecimal price = simulator.simulate(Instrument.MCL);

        // Should be near 1000.00 (within 1%), not near the default 62.40
        BigDecimal lowerBound = new BigDecimal("990.00");
        BigDecimal upperBound = new BigDecimal("1010.00");

        assertTrue(price.compareTo(lowerBound) >= 0 && price.compareTo(upperBound) <= 0,
            "Price " + price + " should be near 1000.00 after setLastPrice");
    }
}
