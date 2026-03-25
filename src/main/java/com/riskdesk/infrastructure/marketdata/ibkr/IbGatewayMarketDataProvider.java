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

    public IbGatewayMarketDataProvider(IbGatewayNativeClient nativeClient, IbGatewayContractResolver contractResolver) {
        this.nativeClient = nativeClient;
        this.contractResolver = contractResolver;
    }

    @Override
    public Map<Instrument, BigDecimal> fetchPrices() {
        Map<Instrument, BigDecimal> prices = new EnumMap<>(Instrument.class);

        for (Instrument instrument : Instrument.values()) {
            try {
                fetchPrice(instrument).ifPresent(price -> prices.put(instrument, price));
            } catch (Exception e) {
                log.warn("IB Gateway snapshot fetch failed for {}: {}", instrument, e.getMessage());
            }
        }

        return prices;
    }

    @Override
    public Optional<BigDecimal> fetchPrice(Instrument instrument) {
        try {
            return contractResolver.resolve(instrument)
                .flatMap(resolved -> nativeClient.requestSnapshotPrice(resolved.contract()));
        } catch (Exception e) {
            log.warn("IB Gateway single snapshot fetch failed for {}: {}", instrument, e.getMessage());
            return Optional.empty();
        }
    }
}
