package com.riskdesk.application.quant.adapter;

import com.riskdesk.application.service.MarketDataService;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.LivePriceSnapshot;
import com.riskdesk.domain.quant.port.LivePricePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Bridges the Quant {@link LivePricePort} onto {@link MarketDataService}.
 * Wrapped in {@link ObjectProvider} so the service is optional during tests
 * (the {@code !test} profile gates {@code MarketDataService} from being created).
 */
@Component
public class LivePricePortAdapter implements LivePricePort {

    private final ObjectProvider<MarketDataService> marketDataServiceProvider;

    public LivePricePortAdapter(ObjectProvider<MarketDataService> marketDataServiceProvider) {
        this.marketDataServiceProvider = marketDataServiceProvider;
    }

    @Override
    public Optional<LivePriceSnapshot> current(Instrument instrument) {
        MarketDataService service = marketDataServiceProvider.getIfAvailable();
        if (service == null) return Optional.empty();
        MarketDataService.StoredPrice stored = service.currentPrice(instrument);
        if (stored == null || stored.price() == null) return Optional.empty();
        return Optional.of(new LivePriceSnapshot(
            stored.price().doubleValue(),
            stored.timestamp(),
            stored.source()
        ));
    }
}
