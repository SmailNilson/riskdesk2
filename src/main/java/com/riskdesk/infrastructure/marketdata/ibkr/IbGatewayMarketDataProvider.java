package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.marketdata.port.MarketDataProvider;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

public class IbGatewayMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(IbGatewayMarketDataProvider.class);

    private final IbGatewayNativeClient nativeClient;
    private final IbGatewayContractResolver contractResolver;
    private final SyntheticDxyCalculator dxyCalculator;

    public IbGatewayMarketDataProvider(IbGatewayNativeClient nativeClient,
                                       IbGatewayContractResolver contractResolver,
                                       SyntheticDxyCalculator dxyCalculator) {
        this.nativeClient = nativeClient;
        this.contractResolver = contractResolver;
        this.dxyCalculator = dxyCalculator;
    }

    @Override
    public Map<Instrument, BigDecimal> fetchPrices() {
        Map<Instrument, BigDecimal> prices = new EnumMap<>(Instrument.class);

        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            try {
                contractResolver.resolve(instrument).ifPresent(resolved -> {
                    nativeClient.ensureStreamingPriceSubscription(resolved.contract());
                    nativeClient.latestStreamingPrice(resolved.contract())
                        .ifPresent(price -> prices.put(instrument, price));
                });
            } catch (Exception e) {
                log.warn("IB Gateway live fetch failed for {}: {}", instrument, e.getMessage());
            }
        }

        // Synthetic DXY from FX pairs
        try {
            dxyCalculator.calculate().ifPresent(dxy -> prices.put(Instrument.DXY, dxy));
        } catch (Exception e) {
            log.warn("Synthetic DXY calculation failed: {}", e.getMessage());
        }

        return prices;
    }

    @Override
    public Optional<BigDecimal> fetchPrice(Instrument instrument) {
        if (instrument.isSynthetic()) {
            try {
                return dxyCalculator.calculate();
            } catch (Exception e) {
                log.warn("Synthetic DXY single fetch failed: {}", e.getMessage());
                return Optional.empty();
            }
        }

        try {
            return contractResolver.resolve(instrument)
                .flatMap(resolved -> {
                    nativeClient.ensureStreamingPriceSubscription(resolved.contract());
                    Optional<BigDecimal> live = nativeClient.latestStreamingPrice(resolved.contract());
                    if (live.isPresent()) {
                        return live;
                    }
                    return nativeClient.requestSnapshotPrice(resolved.contract());
                });
        } catch (Exception e) {
            log.warn("IB Gateway single snapshot fetch failed for {}: {}", instrument, e.getMessage());
            return Optional.empty();
        }
    }
}
