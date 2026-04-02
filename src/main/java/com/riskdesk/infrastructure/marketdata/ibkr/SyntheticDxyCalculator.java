package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.Types.SecType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Computes the US Dollar Index (DXY) synthetically from 6 FX pairs
 * using the official ICE formula:
 *
 *   DXY = 50.14348112
 *         × EURUSD^(-0.576)
 *         × USDJPY^(+0.136)
 *         × GBPUSD^(-0.119)
 *         × USDCAD^(+0.091)
 *         × USDSEK^(+0.042)
 *         × USDCHF^(+0.036)
 *
 * FX rates are streamed from IBKR as CASH contracts on IDEALPRO.
 * Returns {@link Optional#empty()} if any component is unavailable.
 */
public class SyntheticDxyCalculator {

    private static final Logger log = LoggerFactory.getLogger(SyntheticDxyCalculator.class);
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final double DXY_FACTOR = 50.14348112;

    /** FX pair symbol → exponent in the ICE DXY formula. */
    private static final Map<String, Double> COMPONENTS = new LinkedHashMap<>();
    static {
        COMPONENTS.put("EUR", -0.576);
        COMPONENTS.put("GBP", -0.119);
        COMPONENTS.put("CAD",  0.091);
        COMPONENTS.put("SEK",  0.042);
        COMPONENTS.put("CHF",  0.036);
        COMPONENTS.put("JPY",  0.136);
    }

    private final IbGatewayNativeClient nativeClient;
    private final Map<String, Contract> contracts;

    public SyntheticDxyCalculator(IbGatewayNativeClient nativeClient) {
        this.nativeClient = nativeClient;
        this.contracts = buildContracts();
    }

    /**
     * Ensures streaming subscriptions for all 6 FX pairs and computes DXY.
     *
     * @return the synthetic DXY value, or empty if any FX component is missing
     */
    public Optional<BigDecimal> calculate() {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();

        for (Map.Entry<String, Contract> entry : contracts.entrySet()) {
            String pair = entry.getKey();
            Contract contract = entry.getValue();

            nativeClient.ensureStreamingPriceSubscription(contract);
            Optional<BigDecimal> price = nativeClient.latestStreamingPrice(contract);

            if (price.isEmpty() || price.get().compareTo(BigDecimal.ZERO) <= 0) {
                log.debug("Synthetic DXY: missing or invalid price for {}", pair);
                return Optional.empty();
            }
            rates.put(pair, price.get());
        }

        return Optional.of(computeIndex(rates));
    }

    private BigDecimal computeIndex(Map<String, BigDecimal> rates) {
        double result = DXY_FACTOR;

        for (Map.Entry<String, Double> component : COMPONENTS.entrySet()) {
            String symbol = component.getKey();
            double exponent = component.getValue();

            // For XXX/USD pairs (EUR, GBP): IBKR returns the rate as EUR.USD, GBP.USD
            // For USD/XXX pairs (CAD, SEK, CHF, JPY): IBKR returns the rate as USD.CAD, etc.
            BigDecimal rate = rates.get(symbol);
            result *= Math.pow(rate.doubleValue(), exponent);
        }

        return new BigDecimal(result, MC).setScale(3, java.math.RoundingMode.HALF_UP);
    }

    private Map<String, Contract> buildContracts() {
        Map<String, Contract> map = new LinkedHashMap<>();

        // XXX/USD pairs — IBKR symbol is the base currency
        map.put("EUR", cashContract("EUR", "USD"));
        map.put("GBP", cashContract("GBP", "USD"));

        // USD/XXX pairs — IBKR symbol is USD, currency is the quote currency
        map.put("CAD", cashContract("USD", "CAD"));
        map.put("SEK", cashContract("USD", "SEK"));
        map.put("CHF", cashContract("USD", "CHF"));
        map.put("JPY", cashContract("USD", "JPY"));

        return Map.copyOf(map);
    }

    private static Contract cashContract(String symbol, String currency) {
        Contract contract = new Contract();
        contract.symbol(symbol);
        contract.secType(SecType.CASH);
        contract.exchange("IDEALPRO");
        contract.currency(currency);
        return contract;
    }
}
